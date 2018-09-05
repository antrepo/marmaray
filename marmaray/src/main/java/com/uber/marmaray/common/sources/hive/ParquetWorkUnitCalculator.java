/*
 * Copyright (c) 2018 Uber Technologies, Inc.
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package com.uber.marmaray.common.sources.hive;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.uber.marmaray.common.exceptions.JobRuntimeException;
import com.uber.marmaray.common.metadata.HDFSDatePartitionManager;
import com.uber.marmaray.common.metadata.HDFSPartitionManager;
import com.uber.marmaray.common.metadata.IMetadataManager;
import com.uber.marmaray.common.metadata.MetadataConstants;
import com.uber.marmaray.common.metadata.StringValue;
import com.uber.marmaray.common.metrics.DataFeedMetrics;
import com.uber.marmaray.common.metrics.IChargebackCalculator;
import com.uber.marmaray.common.metrics.JobMetrics;
import com.uber.marmaray.common.sources.IWorkUnitCalculator;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ParquetWorkUnitCalculator implements
        IWorkUnitCalculator<String, HiveRunState, ParquetWorkUnitCalculatorResult, StringValue> {

    @Getter
    private Optional<String> nextPartition = Optional.absent();

    @Override
    public void setDataFeedMetrics(final DataFeedMetrics dataFeedMetrics) {
        // ignored, no need to compute data feed level metrics for now.
        // Data is either dispersed or not and row count is tracked as job level metric
    }

    @Override public void setJobMetrics(final JobMetrics jobMetrics) {
        // ignored
    }

    @Override public void setChargebackCalculator(final IChargebackCalculator calculator) {
        // ignored
    }

    @Override
    public void initPreviousRunState(@NonNull final IMetadataManager<StringValue> metadataManager) {
        try {
            if (metadataManager instanceof HDFSPartitionManager) {
                this.nextPartition = ((HDFSPartitionManager) metadataManager).getNextPartition();
            } else if (metadataManager instanceof HDFSDatePartitionManager) {
                this.nextPartition = ((HDFSDatePartitionManager) metadataManager).getNextPartition();
            } else {
                throw new JobRuntimeException("The only supported metadata managers for ParquetWorkUnitCalculator"
                        + " are HDFSPartitionManager or HDFSDataPartitionManager");
            }
        } catch (final IOException e) {
            throw new JobRuntimeException("Unable to get the next partition.  Error message: " + this.nextPartition, e);
        }
    }

    @Override
    public void saveNextRunState(@NonNull final IMetadataManager<StringValue> metadataManager,
                                 @NonNull final HiveRunState nextRunState) {
        /*
         * For the Hive/Parquet use case we don't need the nextRunState information.
         * The current checkpoint saved is used to determine the next partition to process
         * when the next run is processed, especially since we don't know when another hive partition
         * will be added.  There is therefore no need to calculate the next run state during execution of job.
         *
         * Until we add Cassandra metadata information, we assume explicitly this is a HDFSPartitionManager.
         * Todo: T898695 - Implement metadata manager using Cassandra backend
         */
        Preconditions.checkState(metadataManager instanceof HDFSPartitionManager);
        final HDFSPartitionManager partitionManager = (HDFSPartitionManager) metadataManager;

        if (!this.nextPartition.isPresent()) {
            log.warn("No partition was found to process.  Reusing latest checkpoint if exists as checkpoint key");
            if (partitionManager.getLatestCheckpoint().isPresent()) {
                metadataManager.set(MetadataConstants.CHECKPOINT_KEY, partitionManager.getLatestCheckpoint().get());
            }
            return;
        }

        if (partitionManager.isSinglePartition()) {
            metadataManager.set(MetadataConstants.CHECKPOINT_KEY, new StringValue(this.nextPartition.get()));
        } else {
            /*
             * We explicitly always save the latest checkpoint in the metadata file.  Even in cases where
             * we explicitly reprocess a older single existing partition of a Hive table, we write out the latest
             * checkpoint that we have processed so on the next run we can continue processing at the latest point.
             */
            if (checkpointGreaterThanNextPartition(partitionManager.getLatestCheckpoint())) {
                metadataManager.set(MetadataConstants.CHECKPOINT_KEY, partitionManager.getLatestCheckpoint().get());
            } else {
                metadataManager.set(MetadataConstants.CHECKPOINT_KEY, new StringValue(this.nextPartition.get()));
            }
        }
    }

    @Override
    public ParquetWorkUnitCalculatorResult computeWorkUnits() {
        /**
         * The logic for computing work units is pretty straightforward here.
         *
         * We are making the explicit assumption & trade-off for now that each job run
         * only processes data for one partition in Hive.
         *
         * The partition manager will have enough context to automatically determine the
         * next partition to process and this partition will also be saved as the next checkpoint
         * which is why it is returned as the entry in the next run state.  Only if the job succeeds will the
         * value from next run state will be persisted in the metadata as a checkpoint.
         */
        final HiveRunState nextRunState = new HiveRunState(this.nextPartition);
        final List<String> workUnits = nextPartition.isPresent() ? Collections.singletonList(nextPartition.get())
                : Collections.EMPTY_LIST;
        return new ParquetWorkUnitCalculatorResult(workUnits, nextRunState);
    }

    private boolean checkpointGreaterThanNextPartition(@NonNull final Optional<StringValue> checkPoint) {
        if (checkPoint.isPresent()
                && checkPoint.get().getValue().compareTo(this.nextPartition.get()) > 0) {
            return true;
        }
        return false;
    }
}
