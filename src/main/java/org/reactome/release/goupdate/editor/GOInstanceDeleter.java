package org.reactome.release.goupdate.editor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;
import org.reactome.release.goupdate.reports.ObsoleteAccessionReport;
import org.reactome.release.goupdate.utils.CuratorToolAPI;
import org.reactome.release.goupdate.utils.ReferrerDisplayNameGenerator;
import org.reactome.server.graph.domain.model.InstanceEdit;

import java.util.*;
import java.util.stream.Collectors;

import static org.reactome.release.goupdate.utils.ReferrerDisplayNameGenerator.generateDisplayName;
import static org.reactome.release.goupdate.utils.ReferrerDisplayNameGenerator.hasGeneratedDisplayName;
import static org.reactome.release.goupdate.utils.Utils.*;

public class GOInstanceDeleter {
    private static final Logger logger = LogManager.getLogger();
    private static final Logger obsoleteAccessionLogger = LogManager.getLogger("obsoleteAccessionLog");

    private CuratorToolAPI curatorToolAPI;
    private ObsoleteAccessionReport obsoleteAccessionReport;

    public GOInstanceDeleter(CuratorToolAPI curatorToolAPI, ObsoleteAccessionReport obsoleteAccessionReport) {
        this.curatorToolAPI = curatorToolAPI;
        this.obsoleteAccessionReport = obsoleteAccessionReport;
    }

    public void deleteGOInstance(SimpleInstance existingGOInstance) {
        getCuratorToolAPI().deleteInstance(existingGOInstance);
    }

    /**
     * Deletes a GO term from the database.
     * @param goInstance Original GO GKInstance
     * @param obsoleteGoTerm Obsolete GO Term
     * @param replacementGoInstance Replacement GO GKInstance
     * @param referrerLists The referrers of <code>goInstance</code>, by the attribute they refer to it through.
     * @Exception
     */
    public void deleteGoInstance(
        SimpleInstance goInstance,
        ObsoleteGoTerm obsoleteGoTerm,
        SimpleInstance replacementGoInstance,
        Collection<NamedReferrerList> referrerLists
    ) throws Exception {
        if (obsoleteGoTerm.hasReplacedBy()) {
            if (replacementGoInstance != null) {
                pointAllReferrersToOtherInstance(goInstance, replacementGoInstance, referrerLists);
            }

            deleteGOInstance(goInstance);
        } else if (!hasNonGoReferrers(referrerLists)) {
            // But... we still need to clear GO Entity *references* to this.goInstance before deleting THIS
            // instance.
            this.clearAttributesFromReferringGOEntities(goInstance, referrerLists);
            deleteGOInstance(goInstance);
        } else {
            logger.info("GO:{} ({}) is marked as obsolete but there is no replacement value specified! " +
                    "Instance will *NOT* be deleted, as manual clean-up may be necessary.",
                getAccession(goInstance), goInstance.toString());
        }
    }


    public void deleteSecondaryGOInstance(SimpleInstance altGoInst, SimpleInstance primaryGOTerm) {
        try {
            pointAllReferrersToOtherInstance(
                altGoInst, primaryGOTerm, getCuratorToolAPI().getReferrers(altGoInst));

            deleteGOInstance(altGoInst);
        } catch (Exception e) {
            logger.error("Error occurred while trying to delete instance: " + altGoInst, e);
        }
    }

    /**
     * Deletes GO instances that have been flagged for deletion.
     * @param instancesForDeletion - A list of instances that must be deleted.
     * @param obsoleteGoTerm - Obsolete GO term
     * @return The map of undeletable instances.
     * @throws Exception
     */
    public Map<SimpleInstance, Collection<SimpleInstance>> deleteFlaggedInstances(
        List<SimpleInstance> instancesForDeletion,
        ObsoleteGoTerm obsoleteGoTerm,
        SimpleInstance replacementGoInstance
    ) throws Exception {
        Map<SimpleInstance, Collection<SimpleInstance>> undeletable = new HashMap<>();

        for (SimpleInstance instanceForDeletion : instancesForDeletion) {
            // Read once per instance. Deciding whether the instance can be deleted, describing the action taken
            // and then redirecting or clearing the referrers all work from these same lists; reading them for
            // each of those in turn was four or five reads of the same thing.
            Collection<NamedReferrerList> referrerLists = getCuratorToolAPI().getReferrers(instanceForDeletion);

            List<SimpleInstance> goTermReferrers = getReferrersForGoTerm(instanceForDeletion, referrerLists);
            if (!goTermReferrers.isEmpty()) {
                undeletable.put(instanceForDeletion, goTermReferrers);
                continue;
            }

            obsoleteAccessionReport.printObsoleteAccessionRecord(
                instanceForDeletion,
                getAction(referrerLists),
                obsoleteGoTerm.getReplacedByOrConsiderString()
            );
            deleteGoInstance(instanceForDeletion, obsoleteGoTerm, replacementGoInstance, referrerLists);

        }
        return undeletable;
    }

    public void logUndeletableInstances(Map<SimpleInstance, Collection<SimpleInstance>> undeletableInstanceToReferrers) {
        for (SimpleInstance instance : undeletableInstanceToReferrers.keySet()) {
            obsoleteAccessionLogger.info("GO:{} ({}) could not be deleted because it had {} referrers: ",
                instance.getAttribute(ReactomeJavaConstants.identifier),
                instance.getDisplayName(),
                undeletableInstanceToReferrers.get(instance).size()
            );
            for (SimpleInstance referrer : undeletableInstanceToReferrers.get(instance)) {
                InstanceEdit created = referrer.getCreated();
                obsoleteAccessionLogger.info("\t\"{}\", created by {} @ {}",
                    referrer.toString(),
                    created != null ? created.getAuthor().get(0).getDisplayName(): "author not found",
                    created != null ? created.getDateTime() : "unknown datetime"
                );
            }
        }
    }

    private void pointAllReferrersToOtherInstance(
        SimpleInstance originalGOInstance,
        SimpleInstance replacementGOInstance,
        Collection<NamedReferrerList> referrerLists
    ) throws Exception {
        for (NamedReferrerList referrerList : referrerLists) {
            String attributeName = referrerList.getAttributeName();

            for (SimpleInstance referrer : referrerList.getReferrers()) {
                // The referrer could refer to many things via the attribute. We should ONLY remove *this* GO
                // instance (which will probably be deleted) and add the replacement GO term. All other values
                // should be left alone.
                SimpleInstance inflatedReferrer = getCuratorToolAPI().inflate(referrer);
                redirectReferrerAttribute(inflatedReferrer, attributeName, originalGOInstance, replacementGOInstance);

                // PhysicalEntity and CatalystActivity referrers have displayNames derived from the GO term they
                // refer to, so they must be regenerated now that the reference has changed.
                if (hasGeneratedDisplayName(inflatedReferrer)) {
                    inflatedReferrer.setDisplayName(generateDisplayName(inflatedReferrer));
                }

                getCuratorToolAPI().commit(inflatedReferrer);
            }
        }
    }

    private void redirectReferrerAttribute(
        SimpleInstance referrer,
        String attributeName,
        SimpleInstance originalGOInstance,
        SimpleInstance replacementGOInstance
    ) {
        Object currentValue = referrer.getAttribute(attributeName);

        // A multi-valued attribute holds a List; drop *this* GO instance but keep the other values, then add the
        // replacement. A single-valued attribute is simply replaced.
        if (currentValue instanceof List) {
            @SuppressWarnings("unchecked")
            List<SimpleInstance> referrerAttributeValues = ((List<SimpleInstance>) currentValue)
                .stream()
                .filter(attributeValue -> !attributeValue.getDbId().equals(originalGOInstance.getDbId()))
                .collect(Collectors.toList());
            // A shell of the replacement, not the replacement itself: it comes from the map of all GO instances,
            // whose entries refer to each other, and a cycle among them makes the commit's search for new
            // instances to store recurse until the stack runs out.
            referrerAttributeValues.add(toShell(replacementGOInstance));
            referrer.setAttribute(attributeName, referrerAttributeValues);
        } else {
            referrer.setAttribute(attributeName, toShell(replacementGOInstance));
        }
    }


    /*
     * Clears reference attributes that point TO *this* goInstance FROM other GO entities. To be used when an
     * instance is being deleted.
     */
    private void clearAttributesFromReferringGOEntities(
        SimpleInstance originalGOInstance, Collection<NamedReferrerList> referrerLists) throws Exception {

        // From a few tests, it seems that 55 is a good target length to abbreviate to.
        final int abbrevLength = 55;

        for (NamedReferrerList referrerList : referrerLists) {
            String attributeName = referrerList.getAttributeName();

            for (SimpleInstance referrer : referrerList.getReferrers()) {
                SimpleInstance inflatedReferrer = getCuratorToolAPI().inflate(referrer);

                // Only clear references that come FROM other GO entities, so we don't end up with "dangling
                // pointers" in the database.
                if (!isGOEntity(inflatedReferrer)) {
                    continue;
                }

                try {
                    logger.info("CLEARING the attribute {} on \"{}\" because it refers to" +
                            " \"{}\", which is flagged for deletion.",
                        attributeName,
                        abbreviate(inflatedReferrer.toString(), abbrevLength),
                        abbreviate(originalGOInstance.toString(), abbrevLength)
                    );
                    clearReferrerAttribute(inflatedReferrer, attributeName, originalGOInstance);
                    getCuratorToolAPI().commit(inflatedReferrer);
                } catch (Exception e) {
                    logger.error("Error trying to clear {} attribute on \"{}\", referring to \"{}\" " +
                            "(which is to be deleted).",
                        attributeName,
                        abbreviate(inflatedReferrer.toString(), abbrevLength),
                        abbreviate(originalGOInstance.toString(), abbrevLength),
                        e
                    );
                }
            }
        }
    }

    private void clearReferrerAttribute(
        SimpleInstance referrer,
        String attributeName,
        SimpleInstance originalGOInstance
    ) {
        Object currentValue = referrer.getAttribute(attributeName);

        // A multi-valued attribute holds a List; remove *this* instance from the list but leave the other values
        // alone. A single-valued attribute is cleared entirely.
        if (currentValue instanceof List) {
            @SuppressWarnings("unchecked")
            List<SimpleInstance> refVals = ((List<SimpleInstance>) currentValue)
                .stream()
                .filter(refVal -> !refVal.getDbId().equals(originalGOInstance.getDbId()))
                .collect(Collectors.toList());
            referrer.setAttribute(attributeName, refVals);
        } else {
            clearAttribute(referrer, attributeName);
        }
    }


    /**
     * Gets the instances that refer to a GO Term through the attribute that decides whether it can be deleted.
     * The rules (from Peter D.) are:<br/><br/>
     * IF a GO biological process term has NOT been used as a goBiologicalProcess slot value for any event instance
     * in gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
     * IF a GO cellular component term has NOT been used as a compartment slot value for any physical entity or
     * event instance in gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
     * IF a GO molecular function term has NOT been used as the activity slot value for any catalystActivity
     * instance in gk_central, the obsolete GO term instance can be deleted from gk_central.
     * @param instance - the instance to get referrers for.
     * @param referrerLists - the referrers of <code>instance</code>, by the attribute they refer to it through.
     * @return the referrers through that attribute, or an empty list if there are none -- in which case the
     *         instance is deletable, as per the above rules.
     */
    private List<SimpleInstance> getReferrersForGoTerm(
        SimpleInstance instance, Collection<NamedReferrerList> referrerLists) {

        Map<String, String> schemaClassToReferrerAttribute = Map.of(
            ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.goBiologicalProcess,
            ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.compartment,
            ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.activity
        );

        String referrerAttribute = schemaClassToReferrerAttribute.get(instance.getSchemaClassName());
        if (referrerAttribute == null) {
            throw new RuntimeException("Unable to get referrer attribute for " + instance.getSchemaClassName());
        }

        return referrerLists
            .stream()
            .filter(referrerList -> referrerAttribute.equals(referrerList.getAttributeName()))
            .findFirst()
            .map(NamedReferrerList::getReferrers)
            .orElse(Collections.emptyList());
    }

    private String getAction(Collection<NamedReferrerList> referrerLists) {
        return hasNonGoReferrers(referrerLists) ?
            "Automatic Deletion (referrers will be redirected)" :
            "Automatic Deletion (no referrers)";
    }

    private CuratorToolAPI getCuratorToolAPI() {
        return this.curatorToolAPI;
    }
}
