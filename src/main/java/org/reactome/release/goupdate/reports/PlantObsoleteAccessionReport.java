package org.reactome.release.goupdate.reports;

public class PlantObsoleteAccessionReport extends ObsoleteAccessionReport {

    @Override
    String getReportFileNamePrefix() {
        return "plant_obsolete_GO_terms";
    }
}
