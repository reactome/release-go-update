package org.reactome.release.goupdate.editor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.ReactomeJavaConstants;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.utils.CuratorToolAPI;
import org.reactome.release.goupdate.utils.ReferrerDisplayNameGenerator;

import java.util.*;
import java.util.stream.Collectors;

import static org.reactome.release.goupdate.utils.ReferrerDisplayNameGenerator.hasGeneratedDisplayName;
import static org.reactome.release.goupdate.utils.Utils.clearAttribute;
import static org.reactome.release.goupdate.utils.Utils.getAccession;
import static org.reactome.release.goupdate.utils.Utils.refreshInstances;

public class GOInstanceUpdater {
    private static final Logger logger = LogManager.getLogger();
    private static final Logger updatedGOTermLogger = LogManager.getLogger("updatedGOTermsLog");

    private CuratorToolAPI curatorToolAPI;

    public GOInstanceUpdater(CuratorToolAPI curatorToolAPI) {
        this.curatorToolAPI = curatorToolAPI;
    }

    public void updateGOInstance(SimpleInstance existingGOInstance, GoTerm goTerm) throws Exception {
        boolean nameUpdated = stageNameUpdateIfChanged(existingGOInstance, goTerm.getName());
        boolean definitionUpdated = stageDefinitionUpdateIfChanged(existingGOInstance, goTerm.getDef());
        boolean ecNumbersUpdated = stageECNumbersUpdateIfMolecularFunction(existingGOInstance, goTerm.getEcNumbers());

        if (nameUpdated) {
            existingGOInstance.setDisplayName(goTerm.getName());
        }

        if (nameUpdated || definitionUpdated) {
            if (isCellularComponent(existingGOInstance)) {
                setInstanceOfAndComponentOfToNull(existingGOInstance);
            }
        }

        if (nameUpdated || definitionUpdated || ecNumbersUpdated) {
            getCuratorToolAPI().commit(existingGOInstance);

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
    public void updateRelationships(GoTerm goTerm, Map<String, List<SimpleInstance>> allGoInstances) throws Exception {
        List<SimpleInstance> goInstancesForGOTerm = allGoInstances.computeIfAbsent(goTerm.getId(), k -> new ArrayList<>());

        // These instances were read before the update began. Any that have since been committed (by
        // updateGOInstance above, for example) must be re-read before they are changed again, or this commit is
        // rejected as a conflicting change; any that have since been deleted must be dropped, or this commit
        // would re-create them.
        refreshInstances(goInstancesForGOTerm, getCuratorToolAPI());

        for (SimpleInstance goInstanceForGOTerm : goInstancesForGOTerm) {
            if (isCellularComponent(goInstanceForGOTerm)) {
                boolean instanceOfUpdated = updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getIsA(), ReactomeJavaConstants.instanceOf);
                boolean hasPartUpdated = updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getHasPart(), "hasPart");
                boolean componentOfUpdated = updateRelationship(
                    goInstanceForGOTerm, allGoInstances, goTerm.getPartOf(), ReactomeJavaConstants.componentOf);

                if (!instanceOfUpdated && !hasPartUpdated && !componentOfUpdated) {
                    continue;
                }

                getCuratorToolAPI().commit(goInstanceForGOTerm);

                // Now, update the displayName of other instances that refer to this GO Term instance.
                updateReferrerDisplayNames(goInstanceForGOTerm);
            }
        }
    }

    /**
     * Updates the relationships between GO terms in the database.
     * @param goInstance - The GO instance for which to update the relationship
     * @param allGoInstances - Map of all GO instances in the database.
     * @param relationshipAccessions - The GO accessions for the relationship
     * @param reactomeRelationshipName - The name of the relationship can be one of "is_a", "has_part", "part_of",
     *                                   "component_of", "regulates", "positively_regulates", "negatively_regulates".
     * @return true if the relationship's value in the database needs to change, false otherwise.
     */
    private boolean updateRelationship(
        SimpleInstance goInstance,
        Map<String, List<SimpleInstance>> allGoInstances,
        List<String> relationshipAccessions,
        String reactomeRelationshipName
    ) {
        if (relationshipAccessions.isEmpty()) {
            return false;
        }

        Set<Long> originalRelationshipDbIds = getRelationshipDbIds(goInstance, reactomeRelationshipName);

        setRelationshipToNull(goInstance, reactomeRelationshipName);

        List<SimpleInstance> allRelationshipGOInstances = new ArrayList<>();
        for (String relationshipAccession : relationshipAccessions) {
            List<SimpleInstance> relationshipGOInstances =
                getRelationshipGOInstances(allGoInstances, relationshipAccession);

            if (relationshipGOInstances.isEmpty()) {
                updatedGOTermLogger.warn("Trying to set {} on GO:{} ({}) but could not find instance " +
                        "with GO ID = {}. Relationship update could not be completed.",
                    reactomeRelationshipName,
                    goInstance.getAttribute(ReactomeJavaConstants.identifier),
                    goInstance.toString(),
                    relationshipAccession
                );
                continue;
            }

            allRelationshipGOInstances.addAll(relationshipGOInstances);
        }

        goInstance.setAttribute(reactomeRelationshipName, allRelationshipGOInstances);

        // Committing an unchanged instance would add an InstanceEdit to its "modified" slot for a change that
        // never happened, so the relationship is only reported as updated when its value actually differs.
        if (originalRelationshipDbIds.equals(getRelationshipDbIds(goInstance, reactomeRelationshipName))) {
            return false;
        }

        logRelationship(goInstance, reactomeRelationshipName, allRelationshipGOInstances);
        return true;
    }

    private Set<Long> getRelationshipDbIds(SimpleInstance goInstance, String reactomeRelationshipName) {
        Object relationshipValue = goInstance.getAttribute(reactomeRelationshipName);
        if (relationshipValue == null) {
            return new HashSet<>();
        }

        List<SimpleInstance> relationshipInstances = relationshipValue instanceof List ?
            (List<SimpleInstance>) relationshipValue :
            Collections.singletonList((SimpleInstance) relationshipValue);

        return relationshipInstances.stream().map(SimpleInstance::getDbId).collect(Collectors.toSet());
    }

    private List<SimpleInstance> getRelationshipGOInstances(Map<String, List<SimpleInstance>> allGoInstances, String relationshipAccession) {
        List<SimpleInstance> otherInsts = allGoInstances.get(relationshipAccession);
        if (otherInsts != null && !otherInsts.isEmpty()) {
            // Only use the first item, so we don't end up attaching multiple GO Terms with the same
            // accession to this object via "reactomeRelationshipName".
            // I think this is what the Perl code does when it encounters duplicates. Not ideal, but seems
            // to work OK.
            if (otherInsts.size() > 1) {
                otherInsts = otherInsts.subList(0, 1);
            }
        }
        return otherInsts != null ? otherInsts : new ArrayList<>();
    }

    /**
     * Update the Instances that refer to the instance being modified by *this* GoTermInstanceModifier.
     * @throws Exception
     */
    private void updateReferrerDisplayNames(SimpleInstance goInstance) throws Exception {
        for(String referringAttribute : getReferringAttributes(goInstance)) {
            for (SimpleInstance referrer : getReferrers(goInstance, referringAttribute)) {
                if (hasGeneratedDisplayName(referrer)) {
                    updateReferrerDisplayName(referrer);
                }
            }
        }
    }

    private void updateReferrerDisplayName(SimpleInstance referrer) {
        referrer.setDisplayName(ReferrerDisplayNameGenerator.generateDisplayName(referrer));
        getCuratorToolAPI().commit(referrer);
    }

    private boolean stageNameUpdateIfChanged(SimpleInstance existingGOInstance, String newName) {
        List<String> oldNames = (List<String>) existingGOInstance.getAttribute(ReactomeJavaConstants.name);

        String oldName = oldNames != null && !oldNames.isEmpty() ? oldNames.get(0) : "";
        if (newName != null && !newName.equals(oldName)) {
            // "name" is multi-valued in the data model (ExternalOntology.setName takes a List<String>).
            // curator-tool-ws matches the model's set method by the value's own type, so a bare String is not
            // written at all -- and because a commit clears the instance's attributes before re-storing them,
            // passing a String would remove the name from the instance rather than update it.
            existingGOInstance.setAttribute(ReactomeJavaConstants.name, Collections.singletonList(newName));
            return true;
        }
        return false;
    }

    private boolean stageDefinitionUpdateIfChanged(SimpleInstance existingGOInstance, String newDefinition) {
        String oldDefinition = (String) existingGOInstance.getAttribute(ReactomeJavaConstants.definition);

        if (newDefinition != null && !newDefinition.equals(oldDefinition)) {
            existingGOInstance.setAttribute(ReactomeJavaConstants.definition, newDefinition);
            return true;
        }
        return false;
    }

    private boolean stageECNumbersUpdateIfMolecularFunction(SimpleInstance existingGOInstance, List<String> ecNumbers) {

        if (isMolecularFunction(existingGOInstance)) {
            if (ecNumbers != null && !ecNumbers.isEmpty() && !ecNumbers.equals(getECNumbers(existingGOInstance))) {
                existingGOInstance.setAttribute(ReactomeJavaConstants.ecNumber, ecNumbers);

                return true;
            }
        }
        return false;
    }

    private List<String> getECNumbers(SimpleInstance existingGOInstance) {
        Object ecNumberValue = existingGOInstance.getAttribute(ReactomeJavaConstants.ecNumber);
        if (ecNumberValue == null) {
            return Collections.emptyList();
        }

        // "ecNumber" is multi-valued in the data model, but an instance stored while the graph model still
        // declared it single-valued comes back as a lone String.
        return ecNumberValue instanceof List ?
            (List<String>) ecNumberValue :
            Collections.singletonList((String) ecNumberValue);
    }

    private boolean isCellularComponent(SimpleInstance existingGOInstance) {
        return existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent) ||
            existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.Compartment);
    }

    private boolean isMolecularFunction(SimpleInstance existingGOInstance) {
        return existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_MolecularFunction);
    }

    private void setInstanceOfAndComponentOfToNull(SimpleInstance existingGOInstance) throws Exception {
        setRelationshipToNull(existingGOInstance, ReactomeJavaConstants.instanceOf);
        setRelationshipToNull(existingGOInstance, ReactomeJavaConstants.componentOf);
    }

    private void setRelationshipToNull(SimpleInstance goInstance, String reactomeRelationshipName) {
        clearAttribute(goInstance, reactomeRelationshipName);
    }

    private List<String> getReferringAttributes(SimpleInstance goInstance) {
        // The old Perl code only updated PhysicalEntities and CatalystActivities that referred to GO Terms.
        // Events that referred to GO terms via goBiologicalProcess were *not* updated in the old code. So I'm trying
        // to keep this code consistent with that implementation.
        if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_MolecularFunction)) {
            return Collections.singletonList(ReactomeJavaConstants.activity);
        } else if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
            return Collections.singletonList(ReactomeJavaConstants.goCellularComponent);
        } else {
            return new ArrayList<>();
        }
    }

    private List<SimpleInstance> getReferrers(SimpleInstance goInstance, String referringAttributeName)
        throws Exception {

        return getCuratorToolAPI().getReferrers(goInstance, referringAttributeName)
            .stream()
            .map(referrer -> getCuratorToolAPI().inflate(referrer))
            .collect(Collectors.toList());
    }

    private void logRelationship(
        SimpleInstance goInstance,
        String reactomeRelationshipName,
        List<SimpleInstance> relationshipGOInstances
    ) {
        updatedGOTermLogger.info("GO:{} ({}) now has relationship \"{}\" referring to {}",
            getAccession(goInstance),
            goInstance.toString(),
            reactomeRelationshipName,
            getRelationshipGOInstancesAsString(relationshipGOInstances)
        );
    }

    private String getRelationshipGOInstancesAsString(List<SimpleInstance> relationshipGOInstances) {
        return relationshipGOInstances
            .stream()
            .map(this::getRelationshipGOInstanceAsString)
            .collect(Collectors.joining(", "));
    }

    private String getRelationshipGOInstanceAsString(SimpleInstance relationshipGOInstance) {
        return "GO:" + getAccession(relationshipGOInstance) + " (" + relationshipGOInstance.getDisplayName() + ")";
    }

    private CuratorToolAPI getCuratorToolAPI() {
        return this.curatorToolAPI;
    }
}
