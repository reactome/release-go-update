package org.reactome.release.goupdate.reports;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public abstract class Report {
    private String reportsDirectory;
    private CSVPrinter csvPrinter;

    private String dateString;

    public Report() {
        this.reportsDirectory = "reports";
        initReport();
    }

    public void close() throws IOException {
        getCSVPrinter().close();
    }

    abstract List<String> getHeaderColumns();

    abstract String getReportFileNamePrefix();

    CSVPrinter getCSVPrinter() {
        return this.csvPrinter;
    }

    private void initReport() {
        try {
            createReportsDirectory();
            this.csvPrinter = createCSVPrinter();
        } catch (IOException e) {
            throw new RuntimeException("Unable to initialize GO update report " + getClass().getSimpleName(), e);
        }
    }

    private CSVPrinter createCSVPrinter() throws IOException {
        return new CSVPrinter(
            Files.newBufferedWriter(Paths.get(getReportsDirectory() , getReportFileName())),
            getGoReportFormat().withHeader(getHeaderColumns().toArray(new String[0]))
        );
    }

    private String getReportFileName() {
        return getReportFileNamePrefix() + "_" + getDateString() + ".csv";
    }

    private String getReportsDirectory() {
        return this.reportsDirectory;
    }

    private String getDateString() {
        if (this.dateString == null) {
            this.dateString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }

        return this.dateString;
    }

    private CSVFormat getGoReportFormat() {
        return CSVFormat.DEFAULT.withAutoFlush(true).withQuoteMode(QuoteMode.ALL);
    }

    private void createReportsDirectory() throws IOException {
        Files.createDirectories(Paths.get(this.reportsDirectory));
    }
}
