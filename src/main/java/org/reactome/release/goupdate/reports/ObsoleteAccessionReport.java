package org.reactome.release.goupdate.reports;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;

import java.util.Arrays;
import java.util.List;

public class ObsoleteAccessionReport extends Report {

    public void printObsoleteAccessionRecord(
        SimpleInstance goInstance, String action, String replacementGOTermAccession
    ) throws Exception {
        long dbId = goInstance.getDbId();
        String goClassName = goInstance.getSchemaClassName();
        String accession = (String) goInstance.getAttribute(ReactomeJavaConstants.identifier);

        getCSVPrinter().printRecord(dbId, goClassName, accession, action, replacementGOTermAccession);
    }


    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList("DB_ID", "GO Type", "Obsolete Term", "Suggested action", "New/replacement GO Terms");
    }

    @Override
    String getReportFileNamePrefix() {
        return "obsolete_GO_terms";
    }
}
