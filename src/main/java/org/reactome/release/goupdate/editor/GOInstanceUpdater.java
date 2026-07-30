package org.reactome.release.goupdate.editor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.InvalidAttributeValueException;
import org.reactome.release.goupdate.GoUpdateInstanceEditUtils;
import org.reactome.release.goupdate.model.GoTerm;

import java.util.*;
import java.util.stream.Collectors;

public class GOInstanceUpdater {
    private static final Logger logger = LogManager.getLogger();
    private static final Logger updatedGOTermLogger = LogManager.getLogger("updatedGOTermsLog");

    private final MySQLAdaptor adaptor;

    public GOInstanceUpdater(MySQLAdaptor adaptor) {
        this.adaptor = adaptor;
    }

    public void updateGOInstance(GKInstance existingGOInstance, GoTerm goTerm) throws Exception {
        boolean nameUpdated = updateNameIfChanged(existingGOInstance, goTerm.getName());
        boolean definitionUpdated = updateDefinitionIfChanged(existingGOInstance, goTerm.getDef());
        boolean ecNumbersUpdated = updateECNumbers(existingGOInstance, goTerm.getEcNumbers());

        if (nameUpdated || definitionUpdated) {
            if (isCellularComponent(existingGOInstance)) {
                setInstanceOfAndComponentOfToNull(existingGOInstance);
            }
        }

        if (nameUpdated || definitionUpdated || ecNumbersUpdated) {
            addModifiedInstanceEditForExistingGOInstance(existingGOInstance);
            updateDisplayName(existingGOInstance);

            // Referrers might need to be updated, if their DisplayName depends on the GO_* entity which
            // they refer to.
            updateReferrerDisplayNames(existingGOInstance);
        }
    }

    /**
     * Updates the relationships of a GO term.
     * @param goTerm - the GO term
     * @param allGoInstances - a map of ALL GO instances from the database.
     * @throws Exception
     */
    public void updateRelationships(GoTerm goTerm, Map<String, List<GKInstance>> allGoInstances) throws Exception {
        List<GKInstance> goInstancesForGOTerm = allGoInstances.computeIfAbsent(goTerm.getId(), k -> new ArrayList<>());
        for (GKInstance goInstanceForGOTerm : goInstancesForGOTerm) {
            if (goInstanceForGOTerm.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent)) {
                updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getIsA(), ReactomeJavaConstants.instanceOf);
                updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getHasPart(), "hasPart");
                updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getPartOf(), ReactomeJavaConstants.componentOf);

                // Update the instance's "modified".
                goInstanceForGOTerm.getAttributeValuesList(ReactomeJavaConstants.modified);
                GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
                    GoUpdateInstanceEditUtils.GOUpdateInstEditType.UPDATE_RELATIONSHIP, this.getClass());
                goInstanceForGOTerm.addAttributeValue(ReactomeJavaConstants.modified, instEd);
                this.adaptor.updateInstanceAttribute(goInstanceForGOTerm, ReactomeJavaConstants.modified);
                // Now, update the displayName of other instances that refer to this GO Term instance.
                updateReferrerDisplayNames(goInstanceForGOTerm);
            }
        }
    }

    /**
     * Updates the relationships between GO terms in the database.
     * @param goInstance - The GO instance for which to update the relationship
     * @param allGoInstances - Map of all GO instances in the database.
     * @param relationshipIds - The GO accessions for the relationship
     * @param reactomeRelationshipName - The name of the relationship can be one of "is_a", "has_part", "part_of",
     *                                   "component_of", "regulates", "positively_regulates", "negatively_regulates".
     */
    public void updateRelationship(
        GKInstance goInstance,
        Map<String, List<GKInstance>> allGoInstances,
        List<String> relationshipIds,
        String reactomeRelationshipName
    ) {
        if (relationshipIds.isEmpty()) {
            return;
        }

        try {
            // Clear the values that are currently set.
            goInstance.setAttributeValue(reactomeRelationshipName, null);
            this.adaptor.updateInstanceAttribute(goInstance, reactomeRelationshipName);

            for (String relationshipID : relationshipIds) {
                // This is tricky - allGoInstances could contain duplicated GO accessions, because the database
                // could contain multiple GO terms with the same GO accession.
                List<GKInstance> otherInsts = allGoInstances.get(relationshipID);
                if (otherInsts != null && !otherInsts.isEmpty()) {
                    // Only use the first item, so we don't end up attaching multiple GO Terms with the same
                    // accession to this object via "reactomeRelationshipName".
                    // I think this is what the Perl code does when it encounters duplicates. Not ideal, but seems
                    // to work OK.
                    if (otherInsts.size() > 1) {
                        otherInsts = otherInsts.subList(0, 1);
                    }
                    // Add the new value from otherInsts
                    goInstance.addAttributeValue(reactomeRelationshipName, otherInsts);
                    this.adaptor.updateInstanceAttribute(goInstance, reactomeRelationshipName);
                    updatedGOTermLogger.info("GO:{} ({}) now has relationship \"{}\" referring to {}",
                        goInstance.getAttributeValue(ReactomeJavaConstants.accession),
                        goInstance.toString(),
                        reactomeRelationshipName,
                        otherInsts.stream().map(i -> {
                            try {
                                return "GO:" +
                                    i.getAttributeValue(ReactomeJavaConstants.accession).toString() +
                                    " (" + i + ")";
                            } catch (Exception e1) {
                                e1.printStackTrace();
                                return "";
                            }
                        } ).reduce("", (a,b) -> { return a + ", " + b; }));
                } else {
                    updatedGOTermLogger.warn("Trying to set {} on GO:{} ({}) but could not find instance " +
                            "with GO ID = {}. Relationship update could not be completed.",
                        reactomeRelationshipName,
                        goInstance.getAttributeValue(ReactomeJavaConstants.accession),
                        goInstance.toString(),
                        relationshipID
                    );
                }
            }
        } catch (InvalidAttributeValueException e) {
            logger.error(e.getMessage());
            logger.error("Tried to set the '{}' attribute of \"{}\", but this attribute is not valid for this" +
                " object.", reactomeRelationshipName, goInstance.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Update the Instances that refer to the instance being modified by *this* GoTermInstanceModifier.
     * @throws Exception
     */
    private void updateReferrerDisplayNames(GKInstance goInstance) throws Exception {
        for(GKSchemaAttribute referringAttribute : getReferringAttributes(goInstance)) {
            for (GKInstance referrer : getReferrers(goInstance, referringAttribute)) {
                addModifiedInstanceEditForReferrer(referrer);
                updateDisplayName(referrer);
            }
        }
    }

    private boolean updateNameIfChanged(GKInstance existingGOInstance, String newName) throws Exception {
        String oldName = (String) existingGOInstance.getAttributeValue(ReactomeJavaConstants.name);

        if (newName != null && !newName.equals(oldName)) {
            existingGOInstance.setAttributeValue(ReactomeJavaConstants.name, newName);
            this.adaptor.updateInstanceAttribute(existingGOInstance, ReactomeJavaConstants.name);
            return true;
        }
        return false;
    }

    private boolean updateDefinitionIfChanged(GKInstance existingGOInstance, String newDefinition) throws Exception {
        String oldDefinition = (String) existingGOInstance.getAttributeValue(ReactomeJavaConstants.definition);

        if (newDefinition != null && !newDefinition.equals(oldDefinition)) {
            existingGOInstance.setAttributeValue(ReactomeJavaConstants.definition, newDefinition);
            this.adaptor.updateInstanceAttribute(existingGOInstance, ReactomeJavaConstants.definition);
            return true;
        }
        return false;
    }

    private boolean updateECNumbers(GKInstance existingGOInstance, List<String> ecNumbers) throws Exception {
        if (isMolecularFunction(existingGOInstance)) {
            if (ecNumbers != null && !ecNumbers.isEmpty()) {
                existingGOInstance.setAttributeValue(ReactomeJavaConstants.ecNumber, ecNumbers);
                this.adaptor.updateInstanceAttribute(existingGOInstance, ReactomeJavaConstants.ecNumber);

                return true;
            }
        }
        return false;
    }

    private boolean isCellularComponent(GKInstance existingGOInstance) {
        return existingGOInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent);
    }

    private boolean isMolecularFunction(GKInstance existingGOInstance) {
        return existingGOInstance.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction);
    }

    private void setInstanceOfAndComponentOfToNull(GKInstance existingGOInstance) throws Exception {
        existingGOInstance.setAttributeValue(ReactomeJavaConstants.instanceOf, null);
        this.adaptor.updateInstanceAttribute(existingGOInstance, ReactomeJavaConstants.instanceOf);
        existingGOInstance.setAttributeValue(ReactomeJavaConstants.componentOf, null);
        this.adaptor.updateInstanceAttribute(existingGOInstance, ReactomeJavaConstants.componentOf);
    }

    private void addModifiedInstanceEditForExistingGOInstance(GKInstance existingGOInstance) throws Exception {
        GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
            GoUpdateInstanceEditUtils.GOUpdateInstEditType.MODIFIED, this.getClass());
        existingGOInstance.getAttributeValuesList(ReactomeJavaConstants.modified);
        existingGOInstance.addAttributeValue(ReactomeJavaConstants.modified, instEd);
    }

    private void addModifiedInstanceEditForReferrer(GKInstance referrer) throws Exception {
        GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
            GoUpdateInstanceEditUtils.GOUpdateInstEditType.DISPLAY_NAME, this.getClass());
        referrer.getAttributeValuesList(ReactomeJavaConstants.modified);
        referrer.addAttributeValue(ReactomeJavaConstants.modified, instEd);
        this.adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants.modified);
    }

    private void updateDisplayName(GKInstance instance) throws Exception {
        InstanceDisplayNameGenerator.setDisplayName(instance);
        this.adaptor.updateInstanceAttribute(instance, ReactomeJavaConstants._displayName);
    }

    private List<GKSchemaAttribute> getReferringAttributes(GKInstance goInstance) {
        // The old Perl code only updated PhysicalEntities and CatalystActivities that referred to GO Terms.
        // Events that referred to GO terms via goBiologicalProcess were *not* updated in the old code. So I'm trying
        // to keep this code consistent with that implementation.

        @SuppressWarnings("unchecked")
        Set<GKSchemaAttribute> referringAttributes = (Set<GKSchemaAttribute>) goInstance.getSchemClass().getReferers();
        return referringAttributes.stream().filter(
            a -> a.getName().equals(ReactomeJavaConstants.activity) ||
                a.getName().equals(ReactomeJavaConstants.goCellularComponent)
        ).collect(Collectors.toList());
    }

    private List<GKInstance> getReferrers(GKInstance goInstance, GKSchemaAttribute referringAttribute)
        throws Exception {
        @SuppressWarnings("unchecked")
        Collection<GKInstance> referrers =
            (Collection<GKInstance>) goInstance.getReferers(referringAttribute.getName());

        return referrers != null ? new ArrayList<>(referrers) : Collections.emptyList();
    }
}
