package org.reactome.release.goupdate.reports;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ObsoleteAccessionReport extends Report {

    /**
     * Prints a record for an obsolete GO instance. Each suggested replacement accession is written to its own
     * trailing cell so that, when imported into Google Sheets, every accession gets its own hyperlink (a cell can
     * hold only one HYPERLINK formula).
     */
    public void printObsoleteAccessionRecord(
        SimpleInstance goInstance, String action, ObsoleteGoTerm obsoleteGoTerm
    ) throws Exception {
        long dbId = goInstance.getDbId();
        String goClassName = goInstance.getSchemaClassName();
        String accession = (String) goInstance.getAttribute(ReactomeJavaConstants.identifier);

        List<String> replacedBy = obsoleteGoTerm.hasReplacedBy()
            ? Collections.singletonList(obsoleteGoTerm.getReplacedBy())
            : Collections.emptyList();
        List<String> consider = obsoleteGoTerm.getConsider() != null
            ? obsoleteGoTerm.getConsider()
            : Collections.emptyList();

        // Replace-by accession(s) first, then consider accessions, each in its own cell.
        List<String> replacementAccessions = new ArrayList<>(replacedBy);
        replacementAccessions.addAll(consider);

        String replacementType = getReplacementType(replacedBy.size(), consider.size());

        List<String> record = new ArrayList<>(Arrays.asList(
            hyperLinkToCuratorTool(dbId),
            goClassName,
            hyperLinkToQuickGO(accession),
            action,
            replacementType
        ));
        for (String replacementAccession : replacementAccessions) {
            record.add(hyperLinkToQuickGO(replacementAccession.replace("GO:", "").trim()));
        }

        getCSVPrinter().printRecord(record);
    }

    /**
     * Describes the trailing replacement cells, e.g. "Replace by", "Consider" or, when the term has both,
     * "Replace by (1); Consider (2)" so the reader knows which linked cells belong to which kind.
     */
    private String getReplacementType(int replacedByCount, int considerCount) {
        if (replacedByCount > 0 && considerCount > 0) {
            return String.format("Replace by (%d); Consider (%d)", replacedByCount, considerCount);
        } else if (replacedByCount > 0) {
            return "Replace by";
        } else if (considerCount > 0) {
            return "Consider";
        }
        return "N/A";
    }

    @Override
    List<String> getHeaderColumns() {
        return Arrays.asList(
            "DB_ID", "GO Type", "Obsolete Term", "Suggested action", "Replacement type", "New/replacement GO Terms"
        );
    }

    @Override
    String getReportFileNamePrefix() {
        return "obsolete_GO_terms";
    }
}
