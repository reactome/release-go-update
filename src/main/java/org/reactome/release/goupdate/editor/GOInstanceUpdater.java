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
import static org.reactome.release.goupdate.utils.Utils.toShells;

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
        boolean ecNumbersUpdated = stageECNumbersUpdateIfMolecularFunction(existingGOInstance, goTerm.getEcNumber());

        if (nameUpdated) {
            existingGOInstance.setDisplayName(goTerm.getName());
        }

        if (nameUpdated || definitionUpdated || ecNumbersUpdated) {
            getCuratorToolAPI().commit(existingGOInstance);

            // Referrers might need to be updated, if their DisplayName depends on the GO_* entity which
            // they refer to. A referrer's displayName is built from the displayName of the GO term it refers
            // to, so only a change of name can affect it -- a new definition or EC number cannot.
            if (nameUpdated) {
                updateReferrerDisplayNames(existingGOInstance);
            }
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

                // The displayNames of instances referring to this GO Term instance are not updated here: they
                // are built from this instance's displayName, which a relationship change does not alter.
                getCuratorToolAPI().commit(goInstanceForGOTerm);
            }
        }
    }

    /**
     * Updates the relationships between GO terms in the database.
     * @param goInstance - The GO instance for which to update the relationship
     * @param allGoInstances - Map of all GO instances in the database.
     * @param relationshipAccessions - The GO accessions for the relationship. An empty list means the GO file
     *                                 gives the relationship no value, which clears any value the database holds.
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
        Set<Long> originalRelationshipDbIds = getRelationshipDbIds(goInstance, reactomeRelationshipName);

        // Cleared before the file's value is applied, and deliberately left cleared when the file gives no value
        // at all: a relationship the file has stopped listing must lose whatever a previous release stored for
        // it, rather than keeping that value indefinitely.
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

        // Only set when there is a value to set, so that having nothing to store leaves the attribute cleared:
        // clearAttribute removes the attribute outright, which is what a commit needs in order to remove the
        // stored value, and setting it to an empty list would put it back with a value the converter has no use
        // for.
        //
        // Shells, not the instances themselves: the instances come from the map of all GO instances, whose
        // entries refer to each other, and a cycle among them makes the commit's search for new instances to
        // store recurse until the stack runs out.
        if (!allRelationshipGOInstances.isEmpty()) {
            goInstance.setAttribute(reactomeRelationshipName, toShells(allRelationshipGOInstances));
        }

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
            for (SimpleInstance referrer : getCuratorToolAPI().getReferrers(goInstance, referringAttribute)) {
                // hasGeneratedDisplayName only needs the referrer's schema class, which the shell instance
                // returned by getReferrers already carries, so the read that inflates a referrer is only paid
                // for the referrers whose displayName can actually need regenerating.
                if (hasGeneratedDisplayName(referrer)) {
                    updateReferrerDisplayName(getCuratorToolAPI().inflate(referrer));
                }
            }
        }
    }

    private void updateReferrerDisplayName(SimpleInstance referrer) {
        String newDisplayName = ReferrerDisplayNameGenerator.generateDisplayName(referrer);

        // The referrer is read back after the GO term it refers to has been committed, so its regenerated
        // displayName already reflects the new name of that GO term. Committing it when the name works out the
        // same as the stored one would rewrite the instance -- and add an InstanceEdit to its "modified" slot
        // -- for a change that did not happen.
        if (newDisplayName.equals(referrer.getDisplayName())) {
            return;
        }

        referrer.setDisplayName(newDisplayName);
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

    private boolean stageECNumbersUpdateIfMolecularFunction(SimpleInstance existingGOInstance, String ecNumber) {

        if (!isMolecularFunction(existingGOInstance) || ecNumber.equals(getECNumber(existingGOInstance))) {
            return false;
        }

        existingGOInstance.setAttribute(ReactomeJavaConstants.ecNumber, ecNumber);
        return true;
    }

    private String getECNumber(SimpleInstance existingGOInstance) {
        Object ecNumberValue = existingGOInstance.getAttribute(ReactomeJavaConstants.ecNumber);

        return ecNumberValue != null ? ecNumberValue.toString() : "";
    }

    private boolean isCellularComponent(SimpleInstance existingGOInstance) {
        return existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent) ||
            existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.Compartment);
    }

    private boolean isMolecularFunction(SimpleInstance existingGOInstance) {
        return existingGOInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_MolecularFunction);
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

    private void logRelationship(
        SimpleInstance goInstance,
        String reactomeRelationshipName,
        List<SimpleInstance> relationshipGOInstances
    ) {
        if (relationshipGOInstances.isEmpty()) {
            updatedGOTermLogger.info("GO:{} ({}) no longer has relationship \"{}\", which the GO file gives no " +
                    "value for",
                getAccession(goInstance),
                goInstance.toString(),
                reactomeRelationshipName
            );
            return;
        }

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
