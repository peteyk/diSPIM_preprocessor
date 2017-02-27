package dispim_preprocessor;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ImageCalculator;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.micromanager.acquisition.MultipageTiffReader;
import org.micromanager.api.TaggedImageStorage;
import org.micromanager.utils.ImageUtils;
import org.micromanager.utils.MMScriptException;

//Program that takes diSPIM images (in the form of a sequence of large ome-tiffs) 
//as input and splits them into separate stacks for each channel/side

//Dependencies: ij.jar, MMAcqEngine.jar, MMCorej.jar, MMj_.jar


public class DiSPIM_preprocessor {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private int timepoints;
    private final String seriesDirectory;
    
    public static void main(String[] args) throws MMScriptException {
        
        String seriesDirectory = args[0];
        if (!seriesDirectory.substring(seriesDirectory.length() - 1).equals("/")) {
            seriesDirectory += "/";
        }
        
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        int timepoints;
        if (args.length == 6) {
            timepoints = Integer.parseInt(args[5]);
        } else timepoints = 0;   
        
        DiSPIM_preprocessor d = new DiSPIM_preprocessor(x, y, width, height, timepoints, seriesDirectory);
        d.run();
        
    }
    
    public DiSPIM_preprocessor(int x, int y, int width, int height, int timepoints, String seriesDirectory) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.timepoints = timepoints;
        this.seriesDirectory = seriesDirectory;
    }
        
    public void run() {

        File seriesDir = new File(seriesDirectory);
        String omeDirectory = seriesDirectory + "OMES/";
        File omeDir = new File(omeDirectory);
        
        if (!omeDir.exists()) {
            omeDir.mkdir();
            for (File f : seriesDir.listFiles()) {
                f.renameTo(new File(omeDirectory + f.getName()));
            }
        }
        
        File file = new File(omeDirectory + "MMStack_Pos0.ome.tif");

        try {
            MultipageTiffReader tiffReader = new MultipageTiffReader(file);
            JSONObject js = tiffReader.getSummaryMetadata();
            TaggedImageStorage mpt = new TaggedImageStorageMultipageTiff(omeDirectory, false, js, false, true, true);
            export(timepoints, seriesDirectory, mpt, x, y, width, height);
            
        } catch (IOException | JSONException | MMScriptException ex) {
            Logger.getLogger(DiSPIM_preprocessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        renameTiffs(seriesDirectory);
    }

    private void export(int timepoints, String directory, TaggedImageStorage mpt,
            int x, int y, int width, int height)
            throws JSONException, MMScriptException, IOException {

        ImagePlus ip = new ImagePlus("tmp", ImageUtils.makeProcessor(mpt.getImage(0, 0, 0, 0)));
        String baseName = "MVR_STACKS";
        boolean multiPosition = false;
        int position = 0; //will have to add for loop later on to process multiple positions
        boolean firstSideIsA = true;
        if (mpt.getSummaryMetadata().getString("FirstSide").equals("B")) {
            firstSideIsA = false;
        }

        ImageProcessor iProc = ip.getProcessor();
        int nrSides = 0;
        switch (mpt.getSummaryMetadata().getString("NumberOfSides")) {
            case "2":
                nrSides = 2;
                break;
            case "1":
                nrSides = 1;
                break;
            //throw new SaveTaskException("unsupported number of sides");
            default:
                break;
        }
        //Naming for SPIM Registration: spim_TL{ttt}_Channel{c}_Angle{a}.tif
        //spim_TL100_Channel1_Angle90.tif, for example
        //Must be all in same directory
        boolean usesChannels = (mpt.getSummaryMetadata().getInt("Channels") / nrSides) > 1; 
        String[] channelDirArray = new String[mpt.getSummaryMetadata().getInt("Channels")];
        if (usesChannels) {
            for (int c = 0; c < mpt.getSummaryMetadata().getInt("Channels"); c++) {
                String chName = (String) mpt.getSummaryMetadata().getJSONArray("ChNames").get(c);
                String colorName = chName.substring(chName.indexOf("-") + 1); 
                channelDirArray[c] = directory + File.separator + baseName + File.separator
                        + (multiPosition ? ("Pos" + position + File.separator) : "")
                        + "SPIM" + (((c % nrSides) == 0) ? (firstSideIsA ? "A" : "B") : (firstSideIsA ? "B" : "A"))
                        + File.separator + colorName;
            }
        } else {  // two channels are from two views, no need for separate folders for each channel
            channelDirArray[0] = directory + File.separator + baseName + File.separator
                    + (multiPosition ? ("Pos" + position + File.separator) : "")
                    + "SPIM" + (firstSideIsA ? "A" : "B");
            if (nrSides > 1) {
                channelDirArray[1] = directory + File.separator + baseName + File.separator
                        + (multiPosition ? ("Pos" + position + File.separator) : "")
                        + "SPIM" + (firstSideIsA ? "B" : "A");
            }
        }

        for (String dir : channelDirArray) {
            new File(dir).mkdirs();
        }
        
        int numTimepoints;
        if (timepoints == 0) {
            numTimepoints = mpt.lastAcquiredFrame();
        } else numTimepoints = timepoints;
        
        File file = new File(seriesDirectory + File.separator + numTimepoints); //create file showing number of timepoints to process later
        BufferedWriter out = new BufferedWriter(new FileWriter(file)); 
        String st = Integer.toString(numTimepoints);
        out.write(st);
        out.close();
    
        for (int c = 0; c < mpt.getSummaryMetadata().getInt("Channels"); c++) {  // for each channel (4)

            for (int t = 0; t < numTimepoints; t++) {  // for each timepoint (1000)

                ImageStack stack = new ImageStack(width, height);
                for (int i = 0; i < mpt.getSummaryMetadata().getInt("Slices"); i++) {
                    ImageProcessor iProc2 = ImageUtils.makeProcessor(mpt.getImage(c, i, t, 0));

                    //Crop to dimensions from command line args
                    iProc2.setRoi(x, y, width, height);
                    ImageProcessor iProc3 = iProc2.crop();

                    //Add slice to stack
                    stack.addSlice(iProc3);

                }
                ImagePlus ipN = new ImagePlus("tmp", stack);
                ipN.setCalibration(ip.getCalibration());

                //Background subtraction
                //duplicate stack and remove slices containing embryo
                ImagePlus ipTemp = ipN.duplicate();
                ImageStack s = ipTemp.getImageStack();
                removeSlices(s, 4, ipTemp.getImageStackSize() - 2, 1);

                //Make average projection
                ZProjector z = new ZProjector(new ImagePlus("tmp2", s));
                z.setMethod(0);
                z.doProjection();

                //Subtract average from each slice in stack
                ImageCalculator ic = new ImageCalculator();
                ImagePlus imp3 = ic.run("Subtract stack create", ipN, z.getProjection());

                //Finally, save stack
                ij.IJ.save(imp3, channelDirArray[c] + File.separator + "SPIM"
                        + (((c % nrSides) == 0) ? (firstSideIsA ? "A" : "B") : (firstSideIsA ? "B" : "A"))
                        + "-" + t + ".tif");
                System.out.println("Saved " + channelDirArray[c] + File.separator + "SPIM"
                        + (((c % nrSides) == 0) ? (firstSideIsA ? "A" : "B") : (firstSideIsA ? "B" : "A"))
                        + "-" + t + ".tif");
            }
        }
    }

    public void removeSlices(ImageStack stack, int first, int last, int inc) {
        if (last > stack.getSize()) {
            last = stack.getSize();
        }
        int count = 0;
        for (int i = first; i <= last; i += inc) {
            if ((i - count) > stack.getSize()) {
                break;
            }
            stack.deleteSlice(i - count);
            count++;
        }
    }

    private void renameTiffs(String directory) {
        //Rename tiff files from SPIMA/GFP/SPIMA-100.tif, SPIMB/mCherry/SPIMB-100.tif, etc
        //to spim_TL100_Channel0_Angle0.tif, spim_TL100_Channel1_Angle90.tif
        //and place in MVR_STACKS directory for import

        File stackDirectory = new File(directory + File.separator + "MVR_STACKS" + File.separator);
        File sideA = new File(stackDirectory + File.separator + "SPIMA" + File.separator);
        File sideB = new File(stackDirectory + File.separator + "SPIMB" + File.separator);
        File sideAGFP;
        File sideBGFP;
        File sideACherry;
        File sideBCherry;
        File [] channels = sideA.listFiles();
            if (channels[0].getName().contains("output")) {
                sideAGFP = new File(stackDirectory + File.separator + "SPIMA" + File.separator + "output 6 only" + File.separator);
                sideBGFP = new File(stackDirectory + File.separator + "SPIMB" + File.separator + "output 6 only" + File.separator);
                sideACherry = new File(stackDirectory + File.separator + "SPIMA" + File.separator + "output 7 only" + File.separator);
                sideBCherry = new File(stackDirectory + File.separator + "SPIMB" + File.separator + "output 7 only" + File.separator);
            } else {
                sideAGFP = new File(stackDirectory + File.separator + "SPIMA" + File.separator + "GFP" + File.separator);
                sideBGFP = new File(stackDirectory + File.separator + "SPIMB" + File.separator + "GFP" + File.separator);
                sideACherry = new File(stackDirectory + File.separator + "SPIMA" + File.separator + "mCherry" + File.separator);
                sideBCherry = new File(stackDirectory + File.separator + "SPIMB" + File.separator + "mCherry" + File.separator);
            } 

        File[] list1 = sideAGFP.listFiles();
        File[] list2 = sideBGFP.listFiles();
        File[] list3 = sideACherry.listFiles();
        File[] list4 = sideBCherry.listFiles();
        
        processNames(list1, stackDirectory);
        processNames(list2, stackDirectory);
        processNames(list3, stackDirectory);
        processNames(list4, stackDirectory);
        sideAGFP.delete();
        sideBGFP.delete();
        sideACherry.delete();
        sideBCherry.delete();
        sideA.delete();
        sideB.delete();
    }

    private void processNames(File[] list, File stackDirectory) {
        for (File f : list) {
            int channel = 1;
            int angle = 0;
            String s = f.getName().substring(0, f.getName().length() - 4);
            int time = Integer.parseInt(s.substring(6));
            //int length = String.valueOf(time).length();

            if (f.getPath().contains("mCherry") || f.getPath().contains("output 7")) {
                channel = 2;
            } 
            if (f.getPath().contains("SPIMA")) {
                angle = 90;
            }
            
            File newName;
            newName = new File(stackDirectory + File.separator + "spim_TL" + time
                    + "_Channel" + channel + "_Angle" + angle + ".tif");
            f.renameTo(newName);
            
        }
    }
}
