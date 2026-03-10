package com.galafis.analytics;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * Analytics Engine - Data processing, statistical analysis, and reporting.
 *
 * @author Gabriel Demetrios Lafis
 * @version 2.0.0
 */
public class AnalyticsEngine {

    private static final Logger LOGGER = Logger.getLogger(AnalyticsEngine.class.getName());
    private final List<DataPoint> dataPoints = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static class DataPoint {
        private final String id;
        private final String category;
        private final double value;
        private final Map<String, String> dimensions;
        private final LocalDateTime timestamp;

        public DataPoint(String category, double value, Map<String, String> dimensions) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.category = category;
            this.value = value;
            this.dimensions = dimensions != null ? new HashMap<>(dimensions) : new HashMap<>();
            this.timestamp = LocalDateTime.now();
        }

        public String getCategory() { return category; }
        public double getValue() { return value; }
        public Map<String, String> getDimensions() { return dimensions; }
    }

    public static class StatisticalResult {
        public final double mean;
        public final double median;
        public final double stdDev;
        public final double min;
        public final double max;
        public final double variance;
        public final long count;
        public final double sum;
        public final Map<String, Double> percentiles;

        public StatisticalResult(double[] values) {
            Arrays.sort(values);
            this.count = values.length;
            this.sum = Arrays.stream(values).sum();
            this.mean = sum / count;
            this.min = values[0];
            this.max = values[(int) count - 1];
            this.median = count % 2 == 0
                    ? (values[(int) count / 2 - 1] + values[(int) count / 2]) / 2.0
                    : values[(int) count / 2];
            this.variance = Arrays.stream(values).map(v -> Math.pow(v - mean, 2)).sum() / count;
            this.stdDev = Math.sqrt(variance);
            this.percentiles = new LinkedHashMap<>();
            percentiles.put("p25", percentile(values, 25));
            percentiles.put("p50", percentile(values, 50));
            percentiles.put("p75", percentile(values, 75));
            percentiles.put("p90", percentile(values, 90));
            percentiles.put("p95", percentile(values, 95));
            percentiles.put("p99", percentile(values, 99));
        }

        private double percentile(double[] sorted, double p) {
            double idx = (p / 100.0) * (sorted.length - 1);
            int lower = (int) Math.floor(idx);
            int upper = Math.min(lower + 1, sorted.length - 1);
            double frac = idx - lower;
            return sorted[lower] + frac * (sorted[upper] - sorted[lower]);
        }

        @Override
        public String toString() {
            return String.format("Stats{count=%d, mean=%.2f, median=%.2f, stdDev=%.2f, min=%.2f, max=%.2f}",
                    count, mean, median, stdDev, min, max);
        }
    }

    public void ingest(DataPoint point) {
        dataPoints.add(point);
    }

    public void ingestBatch(List<DataPoint> points) {
        dataPoints.addAll(points);
        LOGGER.info("Ingested " + points.size() + " data points (total: " + dataPoints.size() + ")");
    }

    public StatisticalResult computeStatistics(String category) {
        double[] values = dataPoints.stream()
                .filter(dp -> category == null || dp.getCategory().equals(category))
                .mapToDouble(DataPoint::getValue)
                .toArray();
        if (values.length == 0) throw new IllegalArgumentException("No data for category: " + category);
        return new StatisticalResult(values);
    }

    public Map<String, StatisticalResult> computeByCategory() {
        Map<String, List<DataPoint>> grouped = dataPoints.stream()
                .collect(Collectors.groupingBy(DataPoint::getCategory));
        Map<String, StatisticalResult> results = new LinkedHashMap<>();
        grouped.forEach((cat, points) -> {
            double[] values = points.stream().mapToDouble(DataPoint::getValue).toArray();
            results.put(cat, new StatisticalResult(values));
        });
        return results;
    }

    public Map<String, Double> computeAggregation(String dimension, String aggregation) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (DataPoint dp : dataPoints) {
            String key = dp.getDimensions().getOrDefault(dimension, "unknown");
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(dp.getValue());
        }

        Map<String, Double> result = new LinkedHashMap<>();
        grouped.forEach((key, values) -> {
            double aggValue;
            switch (aggregation.toLowerCase()) {
                case "sum": aggValue = values.stream().mapToDouble(Double::doubleValue).sum(); break;
                case "avg": aggValue = values.stream().mapToDouble(Double::doubleValue).average().orElse(0); break;
                case "count": aggValue = values.size(); break;
                case "max": aggValue = values.stream().mapToDouble(Double::doubleValue).max().orElse(0); break;
                case "min": aggValue = values.stream().mapToDouble(Double::doubleValue).min().orElse(0); break;
                default: aggValue = values.stream().mapToDouble(Double::doubleValue).sum(); break;
            }
            result.put(key, Math.round(aggValue * 100.0) / 100.0);
        });
        return result;
    }

    public CompletableFuture<Map<String, Object>> generateReportAsync() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("totalDataPoints", dataPoints.size());
            report.put("categories", dataPoints.stream().map(DataPoint::getCategory).distinct().count());
            report.put("overallStats", computeStatistics(null));
            report.put("byCategory", computeByCategory());
            report.put("generatedAt", LocalDateTime.now().toString());
            return report;
        }, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Java Analytics Engine ===\n");
        AnalyticsEngine engine = new AnalyticsEngine();

        Random rng = new Random(42);
        String[] categories = {"revenue", "costs", "users", "sessions"};
        String[] regions = {"North", "South", "East", "West"};

        List<DataPoint> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, String> dims = new HashMap<>();
            dims.put("region", regions[rng.nextInt(regions.length)]);
            dims.put("channel", rng.nextBoolean() ? "online" : "offline");
            batch.add(new DataPoint(categories[rng.nextInt(categories.length)],
                    rng.nextDouble() * 1000, dims));
        }
        engine.ingestBatch(batch);

        System.out.println("Overall: " + engine.computeStatistics(null));
        System.out.println("\nBy Category:");
        engine.computeByCategory().forEach((cat, stats) ->
                System.out.printf("  %s: mean=%.2f, count=%d%n", cat, stats.mean, stats.count));
        System.out.println("\nRevenue by Region (sum):");
        engine.computeAggregation("region", "sum")
                .forEach((k, v) -> System.out.printf("  %s: $%.2f%n", k, v));

        Map<String, Object> report = engine.generateReportAsync().get();
        System.out.println("\nReport generated at: " + report.get("generatedAt"));
        engine.shutdown();
    }
}
