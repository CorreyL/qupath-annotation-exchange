package qupath.AnnotationExchangeExtension;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import qupath.lib.images.ImageData;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.PolygonROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.util.*;

public class ImportAnnotationServiceJSONPlugin extends AbstractPlugin<BufferedImage> {
    private File annotationFile;
    private String lastMessage = "";

    public ImportAnnotationServiceJSONPlugin(File annotationFile) {
        this.annotationFile = annotationFile;
    }

    @Override
    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
        // Do nothin
    }

    @Override
    public String getName() {
        return "Import Annotation Service JSON";
    }

    @Override
    public String getDescription() {
        return "Plugin for importing JSON Data Made in Spotvision / Annotation Service ";
    }

    @Override
    public String getLastResultsDescription() {
        return lastMessage;
    }

    @Override
    protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {
        return true;
    }

    public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
        List<Class<? extends PathObject>> list = new ArrayList<>(1);
        list.add(PathRootObject.class);
        return list;
    }

    @Override
    protected Collection<? extends PathObject> getParentObjects(PluginRunner<BufferedImage> runner) {
        return Collections.singleton(runner.getHierarchy().getRootObject());
    }

    @Override
    protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                boolean successfulRead = readJSONAnnotations(annotationFile,imageData);
                if(!successfulRead){lastMessage = "JSON annotations not successfully read";}
            }
        };
        tasks.add(runnable);
    }

    @Override
    protected void preprocess(final PluginRunner<BufferedImage> pluginRunner) {}

    @Override
    protected void postprocess(final PluginRunner<BufferedImage> pluginRunner) {}

    private boolean readJSONAnnotations(File inputFile, ImageData imageData ) {
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        try {
            JsonReader jsonReader = new JsonReader(new FileReader(inputFile));
            JsonParser jsonParser = new JsonParser();

            JsonObject jsonAnnotationData = jsonParser.parse(jsonReader).getAsJsonObject();

            JsonArray jsonAnnotations = jsonAnnotationData.get("dictionaries").getAsJsonArray();

            //Loop through every annotation in the dictionary
            for (JsonElement jsonAnnotation : jsonAnnotations) {
                //At the moment we were assuming that the name corresponds to tissue type so we will try to match it up with QuPath types and if none match, create a new one
                String annotationName = jsonAnnotation.getAsJsonObject().get("name").getAsString();

                // Dictionaries are created in the Annotation Service JS library. There isn't a clear convention on what they should mean but we will
                // add a parameter to their imported objects so that they can be used later
                String annotationLabel = jsonAnnotation.getAsJsonObject().get("label").getAsString();

                // Dictionaries are created in the Annotation Service JS library. There isn't a clear convention on what they should mean but we will
                // add a parameter to their imported objects so that they can be used later
                String annotationUID = jsonAnnotation.getAsJsonObject().get("uid").getAsString();

                JsonArray annotationColor = jsonAnnotation.getAsJsonObject().get("path")
                    .getAsJsonObject().get("fillColor")
                    .getAsJsonArray();

                int redChannel = Math.round(annotationColor.get(0).getAsFloat() * 255);
                int greenChannel = Math.round(annotationColor.get(1).getAsFloat() * 255);
                int blueChannel = Math.round(annotationColor.get(2).getAsFloat() * 255);
                int annotationColorInt = ((((redChannel << 8) + greenChannel) << 8) + blueChannel);

                JsonArray segments = jsonAnnotation.getAsJsonObject()
                    .get("path").getAsJsonObject()
                    .get("segments").getAsJsonArray();
                float[] xPoints = new float[segments.size()];
                float[] yPoints = new float[segments.size()];

                //Loop over all coordinates in annotation
                int numOfPoints = 0;
                //Loop through all the coordinates
                for (JsonElement segment : segments) {
                    JsonArray coordinates = segment.getAsJsonArray();
                    // The 0th element of the array is the X coordinate
                    xPoints[numOfPoints] = coordinates.get(0).getAsFloat();
                    // The 1st element of the array is the Y coordinate
                    yPoints[numOfPoints] = coordinates.get(1).getAsFloat();
                    numOfPoints++;
                }

                PathAnnotationObject importedAnnotation;

                // Import the annotation as a Point/Line/Polygon depending on number of coordinates / size
                switch(numOfPoints) {
                    case 1:
                        // Only a single point was found, thus this is a point annotation
                        PointsROI annotaionPoint = new PointsROI(xPoints[0], yPoints[0]);
                        importedAnnotation = new PathAnnotationObject(annotaionPoint);
                        break;
                    case 2:
                        // Two points were found, thus this is a line annotation
                        LineROI annotationLine = new LineROI(xPoints[0],yPoints[0],xPoints[1],yPoints[1]);
                        /**
                         * If the line is really short then we will assume it's a point which was made into a line
                         * by mistake
                         */
                        if (
                            annotationLine.getScaledLength(
                                imageData.getServer().getPixelWidthMicrons(),
                                imageData.getServer().getPixelWidthMicrons()
                            ) < 5
                        ) {
                            PointsROI annotationPointCentroid = new PointsROI(annotationLine.getCentroidX(),annotationLine.getCentroidY());
                            importedAnnotation = new PathAnnotationObject(annotationPointCentroid);
                        } else {
                            importedAnnotation = new PathAnnotationObject(annotationLine);
                        }
                        break;
                    default:
                        // Multiple points were found, thus this is a polygon annotation
                        PolygonROI annotaionPoly = new PolygonROI(xPoints, yPoints, -1, 0, 0);
                        importedAnnotation = new PathAnnotationObject(annotaionPoly);
                        break;
                }

                if (PathClassFactory.pathClassExists(annotationLabel)) {
                    importedAnnotation.setPathClass(PathClassFactory.getPathClass(annotationLabel));
                }

                importedAnnotation.setName(annotationUID);
                importedAnnotation.setColorRGB(annotationColorInt);
                hierarchy.addPathObject(importedAnnotation, true, false);
            }

            hierarchy.fireHierarchyChangedEvent(this);
        } catch(java.io.FileNotFoundException ex){
            lastMessage = "Error Reading JSON File";
            return false;
        }
        return true;
    }
}
