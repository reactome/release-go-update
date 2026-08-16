package org.reactome.release.goupdate.reports;

import org.reactome.release.goupdate.model.GoTerm;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class NewMolecularFunctionReport extends Report {

    public void printNewMFRecord(long dbID, GoTerm goTerm) throws IOException {
        String accession = goTerm.getId();
        String name = goTerm.getName();
        String definition = goTerm.getDef();

        getCSVPrinter().printRecord(dbID, accession, name, definition);
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList("DB_ID", "GO ID", "GO Term Name", "Definition");
    }

    @Override
    String getReportFileNamePrefix() {
        return "new_molecular_functions";
    }
}
