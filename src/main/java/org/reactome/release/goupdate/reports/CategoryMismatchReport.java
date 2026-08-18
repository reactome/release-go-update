package org.reactome.release.goupdate.reports;

import org.gk.model.GKInstance;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.GONamespace;
import org.reactome.release.goupdate.model.GoTerm;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;


public class CategoryMismatchReport extends Report {

    public void printCategoryMismatchRecord(SimpleInstance existingGOInstance, GoTerm goTerm)
        throws IOException {

        long dbId = existingGOInstance.getDbId();
        String accession = goTerm.getId();
        String categoryInDb = existingGOInstance.getSchemaClassName();
        GONamespace categoryInFile = goTerm.getNamespace();

        getCSVPrinter().printRecord(dbId, accession, categoryInDb, categoryInFile);
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList("DB_ID", "GO ID", "Category in Database", "Category in file");
    }

    @Override
    String getReportFileNamePrefix() {
        return "category_mismatch";
    }
}
