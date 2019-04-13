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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import main.java.org.micromanager.plugins.magellan.channels.ChannelSpec;
import main.java.org.micromanager.plugins.magellan.imagedisplay.SubImageControls;
import main.java.org.micromanager.plugins.magellan.json.JSONArray;
import main.java.org.micromanager.plugins.magellan.main.Magellan;
import main.java.org.micromanager.plugins.magellan.misc.Log;
import mmcorej.CMMCore;

/**
 * A single time point acquisition that can dynamically expand in X,Y, and Z
 *
 * @author Henry
 */
public class ExploreAcquisition extends Acquisition {

    private static final int EXPLORE_EVENT_QUEUE_CAP = 2000; //big so you can see a lot of tiles waiting to be acquired

    private volatile double zTop_, zBottom_;
    private ExecutorService eventAdderExecutor_ = Executors.newSingleThreadExecutor();
    private ConcurrentHashMap<Integer, LinkedBlockingQueue<ExploreTileWaitingToAcquire>> queuedTileEvents_ = new ConcurrentHashMap<Integer, LinkedBlockingQueue<ExploreTileWaitingToAcquire>>();
    private ChannelSpec channels_;

    public ExploreAcquisition(ExploreAcqSettings settings) throws Exception {
        super(settings.zStep_, settings.channels_);
        channels_ = settings.channels_;
        try {
            //start at current z position
            zTop_ = Magellan.getCore().getPosition(zStage_);
            zOrigin_ = zTop_;
            zBottom_ = Magellan.getCore().getPosition(zStage_);
        } catch (Exception ex) {
            Log.log("Couldn't get focus device position", true);
            throw new RuntimeException();
        }
        initialize(settings.dir_, settings.name_, settings.tileOverlap_);
    }

    public void clearEventQueue() {
        events_.clear();
        queuedTileEvents_.clear();
    }

    public void abort() {
        if (this.isPaused()) {
            this.togglePaused();
        }
        eventAdderExecutor_.shutdownNow();
        //wait for shutdown
        try {
            //wait for it to exit
            while (!eventAdderExecutor_.awaitTermination(5, TimeUnit.MILLISECONDS)) {
            }
        } catch (InterruptedException ex) {
            Log.log("Unexpected interrupt while trying to abort acquisition", true);
            //shouldn't happen
        }
        //abort all pending events
        events_.clear();
        queuedTileEvents_.clear();
        //signal acquisition engine to start finishigng process
        try {
            events_.put(AcquisitionEvent.createAcquisitionFinishedEvent(this));
            events_.put(AcquisitionEvent.createEngineTaskFinishedEvent());
        } catch (InterruptedException ex) {
            Log.log("Unexpected interrupted exception while trying to abort", true); //shouldnt happen
        }
        imageSink_.waitToDie();
        //image sink will call finish when it completes
    }

    /**
     *
     * @param sliceIndex 0 based slice index
     * @return
     */
    public LinkedBlockingQueue<ExploreTileWaitingToAcquire> getTilesWaitingToAcquireAtSlice(int sliceIndex) {
        return queuedTileEvents_.get(sliceIndex);
    }

    //called by acq engine
    public void eventAcquired(AcquisitionEvent e) {
        //remove from tile queue for overlay drawing purposes
        queuedTileEvents_.get(e.sliceIndex_).remove(new ExploreTileWaitingToAcquire(e.xyPosition_.getGridRow(), e.xyPosition_.getGridCol(),
                e.sliceIndex_, e.channelIndex_));
    }

    public void acquireTileAtCurrentLocation(final SubImageControls controls) {
        eventAdderExecutor_.submit(new Runnable() {

            @Override
            public void run() {
                double xPos, yPos, zPos;
                try {
                    //get current XY and Z Positions
                    zPos = Magellan.getCore().getPosition(Magellan.getCore().getFocusDevice());
                    xPos = Magellan.getCore().getXPosition();
                    yPos = Magellan.getCore().getYPosition();
                } catch (Exception ex) {
                    Log.log("Couldnt get device positions from core");
                    return;
                }
                int sliceIndex = (int) Math.round((zPos - zOrigin_) / zStep_);
                int posIndex = imageStorage_.getPositionIndexFromStageCoords(xPos, yPos);

                for (int channelIndex = 0; channelIndex < Math.max(1, channels_.getNumActiveChannels()); channelIndex++) {
                    if (channels_ != null) {
                        if (!channels_.getActiveChannelSetting(channelIndex).uniqueEvent_) {
                            continue;
                        }
                    }
                    //add tile tile to list waiting to acquire for drawing purposes

                    if (!queuedTileEvents_.containsKey(sliceIndex)) {
                        queuedTileEvents_.put(sliceIndex, new LinkedBlockingQueue<ExploreTileWaitingToAcquire>());
                    }

                    ExploreTileWaitingToAcquire tile = new ExploreTileWaitingToAcquire(imageStorage_.getXYPosition(posIndex).getGridRow(),
                            imageStorage_.getXYPosition(posIndex).getGridCol(), sliceIndex, channelIndex);
                    if (queuedTileEvents_.get(sliceIndex).contains(tile)) {
                        continue; //ignor commands for duplicates
                    }
                    try {
                        queuedTileEvents_.get(sliceIndex).put(tile);
                        //update so taht z scroll bar appears properly
                         minSliceIndex_ = Math.min(minSliceIndex_, sliceIndex);
                         maxSliceIndex_ = Math.max(maxSliceIndex_, sliceIndex);
                         //update so that z limit sliders make sense
                        controls.setZLimitSliderValues(sliceIndex);
                        events_.put(new AcquisitionEvent(ExploreAcquisition.this, 0, channelIndex, sliceIndex, posIndex, 
                                getZCoordinate(sliceIndex) + channels_.getActiveChannelSetting(channelIndex).offset_,
                                imageStorage_.getXYPosition(posIndex)));
                    } catch (InterruptedException e) {
                        //aborted acquisition
                        Log.log("Interrupted while trying to add acquire evenet");
                    }
                }
            }
        });
    }

    public void acquireTiles(final int r1, final int c1, final int r2, final int c2) {
        eventAdderExecutor_.submit(new Runnable() {

            @Override
            public void run() {
                //update positionList and get index
                int[] posIndices = null;
                try {
                    int row1, row2, col1, col2;
                    //order tile indices properly
                    if (r1 > r2) {
                        row1 = r2;
                        row2 = r1;
                    } else {
                        row1 = r1;
                        row2 = r2;
                    }
                    if (c1 > c2) {
                        col1 = c2;
                        col2 = c1;
                    } else {
                        col1 = c1;
                        col2 = c2;
                    }

                    //Get position Indices from manager based on row and column
                    //it will create new metadata as needed
                    int[] newPositionRows = new int[(row2 - row1 + 1) * (col2 - col1 + 1)];
                    int[] newPositionCols = new int[(row2 - row1 + 1) * (col2 - col1 + 1)];
                    for (int r = row1; r <= row2; r++) {
                        for (int c = col1; c <= col2; c++) {
                            int i = (r - row1) + (1 + row2 - row1) * (c - col1);
                            newPositionRows[i] = r;
                            newPositionCols[i] = c;
                        }
                    }
                    posIndices = imageStorage_.getPositionIndices(newPositionRows, newPositionCols);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.log("Problem with position metadata: couldn't add tile", true);
                    return;
                }

                //create set of hardware instructions for an acquisition event
                for (int i = 0; i < posIndices.length; i++) {
                    //update lowest slice for the benefit of the zScrollbar in the viewer
                    updateLowestAndHighestSlices();
                    //Add events for each channel, slice            
                    for (int sliceIndex = getZLimitMinSliceIndex(); sliceIndex <= getZLimitMaxSliceIndex(); sliceIndex++) {
                        for (int channelIndex = 0; channelIndex < Math.max(1, channels_.getNumActiveChannels()); channelIndex++) {
                            if (channels_ != null ) {
                                if (!channels_.getActiveChannelSetting(channelIndex).uniqueEvent_) {
                                    continue;
                                }
                            }
                            try {
                                //in case interupt occurs in between blocking calls of a really big loop
                                if (Thread.interrupted()) {
                                    throw new InterruptedException();
                                }
                                //add tile tile to list waiting to acquire for drawing purposes
                                if (!queuedTileEvents_.containsKey(sliceIndex)) {
                                    queuedTileEvents_.put(sliceIndex, new LinkedBlockingQueue<ExploreTileWaitingToAcquire>());
                                }

                                ExploreTileWaitingToAcquire tile = new ExploreTileWaitingToAcquire(imageStorage_.getXYPosition(posIndices[i]).getGridRow(),
                                        imageStorage_.getXYPosition(posIndices[i]).getGridCol(), sliceIndex, channelIndex);
                                if (queuedTileEvents_.get(sliceIndex).contains(tile)) {
                                    continue; //ignor commands for duplicates
                                }
                                queuedTileEvents_.get(sliceIndex).put(tile);

                                events_.put(new AcquisitionEvent(ExploreAcquisition.this, 0, channelIndex, sliceIndex, posIndices[i], 
                                        getZCoordinate(sliceIndex) + channels_.getActiveChannelSetting(channelIndex).offset_,
                                        imageStorage_.getXYPosition(posIndices[i])));
                            } catch (InterruptedException ex) {
                                //aborted acqusition
                                return;
                            }
                        }
                    }
                }
            }
        });
    }

    public void updateLowestAndHighestSlices() {
        //keep track of this for the purposes of the viewer
        minSliceIndex_ = Math.min(minSliceIndex_, getZLimitMinSliceIndex());
        maxSliceIndex_ = Math.max(maxSliceIndex_, getZLimitMaxSliceIndex());
    }

    /**
     * get min slice index for according to z limit sliders
     *
     * @return
     */
    private int getZLimitMinSliceIndex() {
        return (int) Math.round((zTop_ - zOrigin_) / zStep_);
    }

    /**
     * get max slice index for current settings in explore acquisition
     */
    private int getZLimitMaxSliceIndex() {
        return (int) Math.round((zBottom_ - zOrigin_) / zStep_);
    }

    /**
     * get z coordinate for slice position
     */
    private double getZCoordinate(int sliceIndex) {
        return zOrigin_ + zStep_ * sliceIndex;
    }

    public void setZLimits(double zTop, double zBottom) {
        //Convention: z top should always be lower than zBottom
        zBottom_ = Math.max(zTop, zBottom);
        zTop_ = Math.min(zTop, zBottom);
    }

    public double getZTop() {
        return zTop_;
    }

    public double getZBottom() {
        return zBottom_;
    }

    @Override
    protected JSONArray createInitialPositionList() {
        try {
            //create empty position list that gets filled in as tiles are explored
            CMMCore core = Magellan.getCore();
            JSONArray pList = new JSONArray();
            return pList;
        } catch (Exception e) {
            Log.log("Couldn't create initial position list", true);
            return null;
        }
    }

    @Override
    public int getAcqEventQueueCap() {
        return EXPLORE_EVENT_QUEUE_CAP;
    }

    @Override
    public int getInitialNumFrames() {
        return 1;
    }

    @Override
    public int getInitialNumSlicesEstimate() {
        //Who knows??
        return 1;
    }

    //slice and row/col index of an acquisition event in the queue
    public class ExploreTileWaitingToAcquire {

        public long row, col, sliceIndex, channelIndex;

        public ExploreTileWaitingToAcquire(long r, long c, int z, int ch) {
            row = r;
            col = c;
            sliceIndex = z;
            channelIndex = ch;
        }

        @Override
        public boolean equals(Object other) {
            return ((ExploreTileWaitingToAcquire) other).col == col && ((ExploreTileWaitingToAcquire) other).row == row
                    && ((ExploreTileWaitingToAcquire) other).sliceIndex == sliceIndex && ((ExploreTileWaitingToAcquire) other).channelIndex == channelIndex;
        }

    }
}
