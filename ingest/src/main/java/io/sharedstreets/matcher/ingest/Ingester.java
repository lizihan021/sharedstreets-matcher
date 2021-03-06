package io.sharedstreets.matcher.ingest;

import io.sharedstreets.matcher.ingest.input.CsvEventExtractor;
import io.sharedstreets.matcher.ingest.input.JsonEventExtractor;
import io.sharedstreets.matcher.ingest.input.gpx.GpxInputFormat;
import io.sharedstreets.matcher.ingest.model.Ingest;
import io.sharedstreets.matcher.ingest.model.InputEvent;
import io.sharedstreets.matcher.ingest.util.TileId;
import org.apache.commons.cli.*;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.io.FileOutputFormat;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Ingester {

    static Logger logger = LoggerFactory.getLogger(Ingester.class);

    public static void main(String[] args) throws Exception {
        // create the command line parser
        CommandLineParser parser = new DefaultParser();

        // create the Options
        Options options = new Options();

        options.addOption( OptionBuilder.withLongOpt( "input" )
                .withDescription( "path to input files" )
                .hasArg()
                .withArgName("INPUT-DIR")
                .create() );

        options.addOption( OptionBuilder.withLongOpt( "type" )
                .withDescription( "input type, supports: [CSV, JSON, GPX]" )
                .hasArg()
                .withArgName("INPUT-DIR")
                .create() );

        options.addOption( OptionBuilder.withLongOpt( "output" )
                .withDescription( "path to output (will be overwritten)" )
                .hasArg()
                .withArgName("OUTPUT-DIR")
                .create() );

        options.addOption("speeds", "track GPS speed when available");

        String inputPath = "";

        String outputPath = "";

        String inputType = "";

        boolean gpsSpeeds = false;

        try {
            // parse the command line arguments
            CommandLine line = parser.parse( options, args );



            if( line.hasOption( "input" ) ) {
                // print the value of block-size
                inputPath = line.getOptionValue( "input" );
            }

            if( line.hasOption( "output" ) ) {
                // print the value of block-size
                outputPath = line.getOptionValue( "output" );
            }

            if( line.hasOption( "speeds" ) ) {
                // print the value of block-size
                gpsSpeeds = true;
            }

            if( line.hasOption( "type" ) ) {
                // print the value of block-size
                inputType = line.getOptionValue( "type" ).trim().toUpperCase();
            }
            else {
                String fileParts[] = inputPath.split("\\.");
                if(fileParts[fileParts.length-1].toLowerCase().equals("csv"))
                    inputType = "CSV";
                else if(fileParts[fileParts.length-1].toLowerCase().equals("json"))
                    inputType = "JSON";
                else if(fileParts[fileParts.length-1].toLowerCase().equals("gpx"))
                    inputType = "GPX";
            }

        }
        catch( Exception exp ) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "integster", options );

            System.out.println( "Unexpected exception:" + exp.getMessage() );
            return;
        }

        final String finalInputType = inputType;

        // let's go...

        logger.info("Starting up streams...");

        ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        if(inputPath == null || inputPath.trim().isEmpty()) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "integster", options );
            return;
        }

        // process events

        // step 1: read strings (blocks of location events from file)
        DataSet<InputEvent> inputEvents = null;

        if (finalInputType.equals("GPX")) {
            inputEvents = env.createInput(new GpxInputFormat(inputPath, gpsSpeeds));
        }
        else {
            DataSet<String> inputStream = env.readTextFile(inputPath);

            // open text based file formats and map strings to extractor methods
            inputEvents = inputStream.flatMap(new FlatMapFunction<String, InputEvent>() {

                @Override
                public void flatMap(String value, Collector<InputEvent> out) throws Exception {

                    if (finalInputType.equals("CSV")) {
                        List<InputEvent> inputEvents = CsvEventExtractor.extractEvents(value);

                        for (InputEvent inputEvent : inputEvents)
                            out.collect(inputEvent);

                    } else if (finalInputType.equals("JSON")) {
                        List<InputEvent> inputEvents = JsonEventExtractor.extractEvents(value);

                        for (InputEvent inputEvent : inputEvents)
                            out.collect(inputEvent);
                    }
                }
            });
        }

        // create list of map tiles for input traces
        DataSet<TileId> tileIds = inputEvents.map(new MapFunction<InputEvent, TileId>(){

            @Override
            public TileId map(InputEvent value) throws Exception {
                return TileId.lonLatToTileId(12, value.point.lon, value.point.lat);
            }

        }).distinct();

        // map IDs to tile URLs
        DataSet<String> tileUrls = tileIds.map(new MapFunction<TileId, String>() {
            @Override
            public String map(TileId value) throws Exception {
                // TODO allow selection of tile build -- defaulting to current build for moment
                return "https://tiles.sharedstreets.io/planet-180312/" + value.toString() + ".geometry.pbf";
            }
        });

        Path mapTilePath = Paths.get(outputPath, "tile_set.txt").toAbsolutePath();

        if(mapTilePath.toFile().exists()) {
            System.out.print("File already exists: " + mapTilePath.toString());
            return;
        }

        // write tile URLs to disk (can be read by curl "xargs -n 1 curl -O < tile_set.txt")
        tileUrls.writeAsText(mapTilePath.toString()).setParallelism(1);

        Path dataPath = Paths.get(outputPath, "event_data").toAbsolutePath();

        if(dataPath.toFile().exists()) {
            System.out.print("File already exists: " + mapTilePath.toString());
            return;
        }

        // write protobuf of traces
        inputEvents.write(new FileOutputFormat<InputEvent>() {
            @Override
            public void writeRecord(InputEvent record) throws IOException {
                Ingest.InputEventProto proto = record.toProto();
                proto.writeDelimitedTo(this.stream);
            }
        }, dataPath.toString()).setParallelism(1);

        env.execute("process");

    }
}
