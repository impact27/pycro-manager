///////////////////////////////////////////////////////////////////////////////
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package main.java.org.micromanager.plugins.magellan.acq;

import ij.IJ;
import main.java.org.micromanager.plugins.magellan.imagedisplay.DisplayPlus;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import main.java.org.micromanager.plugins.magellan.channels.ChannelSetting;
import java.awt.Color;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import main.java.org.micromanager.plugins.magellan.channels.ChannelSpec;
import main.java.org.micromanager.plugins.magellan.json.JSONArray;
import main.java.org.micromanager.plugins.magellan.json.JSONObject;
import main.java.org.micromanager.plugins.magellan.main.Magellan;
import main.java.org.micromanager.plugins.magellan.misc.MD;

/**
 * Abstract class that manages a generic acquisition. Subclassed into specific
 * types of acquisition
 */
public abstract class Acquisition implements AcquisitionEventSource{

   //max numberof images that are held in queue to be saved
   private static final int OUTPUT_QUEUE_SIZE = 40;
   
   protected final double zStep_;
   protected double zOrigin_;
   protected volatile int minSliceIndex_ = 0, maxSliceIndex_ = 0;
   private BlockingQueue<MagellanTaggedImage> engineOutputQueue_;
   protected String xyStage_, zStage_;
   protected boolean zStageHasLimits_ = false;
   protected double zStageLowerLimit_, zStageUpperLimit_;
   protected LinkedBlockingDeque<AcquisitionEvent> events_;
   protected AcquisitionEvent lastEvent_ = null;
   protected TaggedImageSink imageSink_;
   protected volatile boolean finished_ = false;
   private String name_;
   private long startTime_ms_ = -1;
   protected MultiResMultipageTiffStorage imageStorage_;
   private int overlapX_, overlapY_;
   private volatile boolean pause_ = false;
   private Object pauseLock_ = new Object();
   protected ChannelSpec channels_;
   private volatile MagellanTaggedImage lastImage_;

   public Acquisition(double zStep, ChannelSpec channels) throws Exception {
      xyStage_ = Magellan.getCore().getXYStageDevice();
      zStage_ = Magellan.getCore().getFocusDevice();
      channels_ = channels;
      //"postion" is not generic name..and as of right now there is now way of getting generic z positions
      //from a z deviec in MM
      String positionName = "Position";
       if (Magellan.getCore().hasProperty(zStage_, positionName)) {
           zStageHasLimits_ = Magellan.getCore().hasPropertyLimits(zStage_, positionName);
           if (zStageHasLimits_) {
               zStageLowerLimit_ = Magellan.getCore().getPropertyLowerLimit(zStage_, positionName);
               zStageUpperLimit_ = Magellan.getCore().getPropertyUpperLimit(zStage_, positionName);
           }
       }
      zStep_ = zStep;
      events_ = new LinkedBlockingDeque<AcquisitionEvent>(getAcqEventQueueCap());
   }
   
   public abstract int getAcqEventQueueCap();

    /**
    * Get initial number of frames (but this can change during acq)
    * @return 
    */
   public abstract int getInitialNumFrames();
   
   public abstract int getInitialNumSlicesEstimate();
   
   protected abstract JSONArray createInitialPositionList();
   
   public abstract void abort();

   public AcquisitionEvent getNextEvent() throws InterruptedException {
      synchronized (pauseLock_) {
         while (pause_) {
            pauseLock_.wait();
         }
      }   
      AcquisitionEvent event = events_.take();
      lastEvent_ = event;
      return event;
   }
   
   public MultiResMultipageTiffStorage getStorage() {
      return imageStorage_;
   }

   public String getXYStageName() {
       return xyStage_;
   }
   
   public String getZStageName() {
       return zStage_;
   }

   /**
    * indices are 1 based and positive
    *
    * @param sliceIndex -
    * @param frameIndex -
    * @return
    */
   public double getZCoordinateOfDisplaySlice(int displaySliceIndex) {
      displaySliceIndex += minSliceIndex_;
      return zOrigin_ + zStep_ * displaySliceIndex;
   }
   
    public int getDisplaySliceIndexFromZCoordinate(double z) {
        return (int) Math.round((z - zOrigin_) / zStep_) - minSliceIndex_;
    }
   /**
    * Return the maximum number of possible channels for the acquisition, not all of which are neccessarily active
    * @return 
    */
   public int getNumChannels() {
      return channels_.getNumActiveChannels();
   }
   
   public ChannelSpec getChannels() {
      return channels_;
   }
   
   public int getNumSlices() {
      return maxSliceIndex_ - minSliceIndex_ + 1;
   }

   public int getMinSliceIndex() {
      return minSliceIndex_;
   }

   public int getMaxSliceIndex() {
      return maxSliceIndex_;
   }
   
   public boolean isFinished() {
      return finished_;
   }
      
   public void markAsFinished() {
      lastImage_ = null;
      finished_ = true;
   }
   public long getStartTime_ms() {
      return startTime_ms_;
   }
   
   public void setStartTime_ms(long time) {
      startTime_ms_ = time;
   }
   
   public int getOverlapX() {
      return overlapX_;
   }
   
   public int getOverlapY() {
      return overlapY_;
   }
   
   public void waitUntilClosed() {
       imageSink_.waitToDie();
   }
   
   protected void initialize(String dir, String name, double overlapPercent) {
      engineOutputQueue_ = new LinkedBlockingQueue<MagellanTaggedImage>(OUTPUT_QUEUE_SIZE);
      overlapX_ = (int) (Magellan.getCore().getImageWidth() * overlapPercent / 100);
      overlapY_ = (int) (Magellan.getCore().getImageHeight() * overlapPercent / 100);
      JSONObject summaryMetadata = MagellanEngine.makeSummaryMD(this, name);
      imageStorage_ = new MultiResMultipageTiffStorage(dir, summaryMetadata,
              (this instanceof FixedAreaAcquisition)); //estimatye background pixel values for fixed acqs but not explore
      //storage class has determined unique acq name, so it can now be stored
      name_ = imageStorage_.getUniqueAcqName();
      MMImageCache imageCache = new MMImageCache(imageStorage_);
      imageCache.setSummaryMetadata(summaryMetadata);
      new DisplayPlus(imageCache, this, summaryMetadata, imageStorage_);         
      imageSink_ = new TaggedImageSink(engineOutputQueue_, imageCache, this);
      imageSink_.start();
   }
   
   public String getName() {
      return name_;
   }

   public double getZStep() {
      return zStep_;
   }
   
   public MagellanTaggedImage getLastImage() {
       return lastImage_;
   }

   public void addImage(MagellanTaggedImage img) {
      try {
         lastImage_ = img;
         engineOutputQueue_.put(img);
      } catch (InterruptedException ex) {
         IJ.log("Acquisition engine thread interrupted");
      }
   }

   public boolean isPaused() {
      return pause_; 
   }
   
   public void togglePaused() {
      pause_ = !pause_;
      synchronized (pauseLock_) {
         pauseLock_.notifyAll();
      }
   }
   
   public String[] getChannelNames() {
      return channels_.getActiveChannelNames();
   }
   
    public Color[] getChannelColors() {
      return channels_.getActiveChannelColors();
    }

}
