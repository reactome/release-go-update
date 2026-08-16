package org.reactome.release.goupdate.reports;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class ReplacedGOTermsReport extends Report {

    public void printReplacedGOTermsRecord(
        long primaryDbId, String primaryDisplayName, String primaryAccession, String primaryGOClassName,
        long secondaryDbId, String secondaryAccession, String secondaryGOClassName, String referrers
    ) throws IOException {
        getCSVPrinter().printRecord(primaryDbId, primaryDisplayName, primaryAccession, primaryGOClassName,
            secondaryDbId, secondaryAccession, secondaryGOClassName, referrers);
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList(
            "DB_ID",
            "GO Term Name",
            "Primary accession",
            "Primary Class",
            "DB_ID (Secondary; to be deleted)",
            "Secondary accession (to be deleted)",
            "Secondary Class",
            "Referrers to be automatically redirected to Primary accession"
        );
    }

    @Override
    String getReportFileNamePrefix() {
        return "replaced_GO_terms";
    }
}
