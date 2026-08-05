package org.reactome.release.goupdate.utils;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;

import java.util.List;

/**
 * Regenerates the displayName of instances whose displayName is derived from the GO term they refer to. When a GO
 * term is deleted or updated, its PhysicalEntity and CatalystActivity referrers need their displayNames refreshed to
 * reflect the change.
 */
public class ReferrerDisplayNameGenerator {

    /**
     * Returns true if the referrer's displayName is derived from the GO term it refers to (i.e., it is a
     * PhysicalEntity or CatalystActivity) and therefore needs regenerating when that reference changes.
     * @param referrer - the referring instance to check.
     * @return true if <code>referrer</code> has a generated displayName, false otherwise.
     */
    public static boolean hasGeneratedDisplayName(SimpleInstance referrer) {
        return isCatalystActivity(referrer) || isPhysicalEntity(referrer);
    }

    /**
     * Generates the displayName for a PhysicalEntity or CatalystActivity referrer.
     * @param referrer - the referring instance.
     * @return the generated displayName.
     * @throws RuntimeException if <code>referrer</code> is neither a PhysicalEntity nor a CatalystActivity.
     */
    public static String generateDisplayName(SimpleInstance referrer) {
        if (isCatalystActivity(referrer)) {
            return getCatalystActivityName(referrer);
        } else if (isPhysicalEntity(referrer)) {
            return getPhysicalEntityName(referrer);
        } else {
            throw new RuntimeException("Unable to generate display name for referrer " + referrer);
        }
    }

    public static boolean isCatalystActivity(SimpleInstance instance) {
        return instance.getSchemaClassName().equals(ReactomeJavaConstants.CatalystActivity);
    }

    public static boolean isPhysicalEntity(SimpleInstance instance) {
        List<String> physicalEntitySubclasses = List.of(
            ReactomeJavaConstants.Cell,
            ReactomeJavaConstants.Complex,
            ReactomeJavaConstants.ChemicalDrug,
            ReactomeJavaConstants.ProteinDrug,
            ReactomeJavaConstants.RNADrug,
            ReactomeJavaConstants.CandidateSet,
            ReactomeJavaConstants.DefinedSet,
            ReactomeJavaConstants.GenomeEncodedEntity,
            ReactomeJavaConstants.EntityWithAccessionedSequence,
            ReactomeJavaConstants.OtherEntity,
            ReactomeJavaConstants.Polymer,
            ReactomeJavaConstants.SimpleEntity
        );

        return physicalEntitySubclasses.contains(instance.getSchemaClassName());
    }

    private static String getCatalystActivityName(SimpleInstance instance) {
        StringBuffer buffer = new StringBuffer();
        SimpleInstance activity = (SimpleInstance) instance.getAttribute("activity");
        String actName = activity != null ? activity.getDisplayName() : "unknown";

        buffer.append(actName);
        if (!actName.toLowerCase().contains("activity")) { // need activity
            buffer.append(" activity ");
        } else {
            buffer.append(" ");
        }
        buffer.append("of");
        SimpleInstance physicalEntity = (SimpleInstance) instance.getAttribute("physicalEntity");
        if (physicalEntity == null)
            buffer.append(" unknown entity");
        else {
            buffer.append(" " + physicalEntity.getDisplayName());
        }
        return buffer.toString();
    }

    private static String getPhysicalEntityName(SimpleInstance physicalEntity) {
        StringBuffer buffer = new StringBuffer();
        List<String> names = (List<String>) physicalEntity.getAttribute("name");
        if (names != null && !names.isEmpty()) {
            buffer.append(names.get(0));
        } else {
            buffer.append("unknown");
        }
        // Check compartment
        List<SimpleInstance> compartments =
            (List<SimpleInstance>) physicalEntity.getAttribute(ReactomeJavaConstants.compartment);
        if (compartments != null && !compartments.isEmpty()) {
            SimpleInstance compartment = compartments.get(0);
            buffer.append(" [");
            buffer.append(compartment.getDisplayName());
            buffer.append("]");
        }

        return buffer.toString();
    }
}