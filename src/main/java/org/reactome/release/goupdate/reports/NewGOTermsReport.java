package org.reactome.release.goupdate.reports;

import org.reactome.release.goupdate.model.GoTerm;

import java.io.IOException;

import java.util.Arrays;
import java.util.List;

public class NewGOTermsReport extends Report {

    public void printNewGOTermRecord(long dbID, GoTerm goTerm) throws IOException {
        String name = goTerm.getName();
        String accession = goTerm.getId();
        String namespace = goTerm.getNamespace().getReactomeName();
        String definition = goTerm.getDef();

        getCSVPrinter().printRecord(dbID, name, accession, namespace, definition);
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList("DB_ID", "GO Term Name", "GO Term ID", "GO Term Type", "Definition");
    }

    @Override
    String getReportFileNamePrefix() {
        return "new_GO_terms";
    }
}
