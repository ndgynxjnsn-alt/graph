import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class FileAnalysisService {

    private final DistributionSummary s3DownloadSizeSummary;
    private final DistributionSummary responseSizeSummary;

    public FileAnalysisService(MeterRegistry meterRegistry) {
        // Metric for S3 Download Size
        this.s3DownloadSizeSummary = DistributionSummary.builder("s3.download.size")
                .description("Size of files downloaded from S3")
                .baseUnit("bytes")
                .publishPercentileHistogram() // Crucial for Mimir/Prometheus histograms
                .register(meterRegistry);

        // Metric for HTTP Response Size
        this.responseSizeSummary = DistributionSummary.builder("http.response.size")
                .description("Size of the HTTP response body sent to the caller")
                .baseUnit("bytes")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public byte[] processAndRespond(String s3Link) {
        // 1. Download file from S3
        byte[] s3FileBytes = downloadFromS3(s3Link);
        
        // Track the S3 file size in bytes
        s3DownloadSizeSummary.record(s3FileBytes.length);

        // 2. Analyze the file
        byte[] responsePayload = analyzeFile(s3FileBytes);

        // 3. Track the HTTP response size right before sending
        responseSizeSummary.record(responsePayload.length);

        return responsePayload;
    }

    // Mock methods for context
    private byte[] downloadFromS3(String link) { return new byte[5000]; }
    private byte[] analyzeFile(byte[] data) { return new byte[1024]; }
}


rate(s3_download_size_bytes_sum[5m]) 
/ 
rate(s3_download_size_bytes_count[5m])

histogram_quantile(0.95, sum by (le) (rate(http_response_size_bytes_bucket[5m])))








import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class FileAnalysisService {

    private final DistributionSummary s3DownloadSizeSummary;
    private final DistributionSummary responseSizeSummary;

    // Define a multiplier for Megabytes (using standard 1024 binary multiplier)
    private static final double MB = 1024.0 * 1024.0;

    public FileAnalysisService(MeterRegistry meterRegistry) {
        
        // Use an array of specific boundaries to keep the builder clean
        double[] sizeBuckets = new double[] {
            0.5 * MB, 
            1.0 * MB, 
            2.0 * MB, 
            4.0 * MB, 
            8.0 * MB, 
            16.0 * MB
        };

        this.s3DownloadSizeSummary = DistributionSummary.builder("s3.download.size")
                .description("Size of files downloaded from S3")
                .baseUnit("bytes")
                // Define your explicit buckets here
                .serviceLevelObjectives(sizeBuckets) 
                .register(meterRegistry);

        this.responseSizeSummary = DistributionSummary.builder("http.response.size")
                .description("Size of the HTTP response body sent to the caller")
                .baseUnit("bytes")
                .serviceLevelObjectives(sizeBuckets)
                .register(meterRegistry);
    }
    
    // ... rest of your processAndRespond method remains exactly the same
}