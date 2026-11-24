package com.acme.orderplatform.recon.job;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Splits the day's orders into N disjoint buckets by hash of the order id. Each bucket is a worker
 * step with its own checkpoint, so they run in parallel and a failed bucket restarts on its own.
 * To scale further, the same partitions can be sent to remote workers instead of local threads.
 */
public class HashPartitioner implements Partitioner {

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();
        for (int i = 0; i < gridSize; i++) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putInt("partition", i);
            ctx.putInt("gridSize", gridSize);
            partitions.put("bucket-" + i, ctx);
        }
        return partitions;
    }
}
