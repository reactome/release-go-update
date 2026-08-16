package org.reactome.release.goupdate.reports;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class DuplicatesReport extends Report {

    public void printDuplicateRecord(
        long dbId, String displayName, String accession, String goClassName, String when, int numOfReferrers
    ) throws IOException {
        getCSVPrinter().printRecord(dbId, displayName, accession, goClassName, when, numOfReferrers);
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList(
            "DB_ID", "Name", "Accession", "GO type", "Before or After GO Update process?", "Number of referrers");
    }

    @Override
    String getReportFileNamePrefix() {
        return "duplicate_GO_terms";
    }

}
