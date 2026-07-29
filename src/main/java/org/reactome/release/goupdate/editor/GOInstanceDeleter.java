package org.reactome.release.goupdate.editor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.SchemaClass;
import org.reactome.release.goupdate.GoUpdateInstanceEditUtils;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;
import org.reactome.release.goupdate.reports.ObsoleteAccessionReport;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.reactome.release.goupdate.utils.Utils.*;
import static org.reactome.release.goupdate.utils.Utils.isNotGOEntity;

public class GOInstanceDeleter {
    private static final Logger logger = LogManager.getLogger();
    private static final Logger obsoleteAccessionLogger = LogManager.getLogger("obsoleteAccessionLog");

    private MySQLAdaptor adaptor;
    private ObsoleteAccessionReport obsoleteAccessionReport;

    public GOInstanceDeleter(MySQLAdaptor adaptor, ObsoleteAccessionReport obsoleteAccessionReport) {
        this.adaptor = adaptor;
        this.obsoleteAccessionReport = obsoleteAccessionReport;
    }

    public void deleteGOInstance(GKInstance existingGOInstance) throws Exception {
        adaptor.deleteByDBID(existingGOInstance.getDBID());
    }

    /**
     * Deletes a GO term from the database.
     * @param goInstance Original GO GKInstance
     * @param obsoleteGoTerm Obsolete GO Term
     * @param replacementGoInstance Replacement GO GKInstance
     * @Exception
     */
    public void deleteGoInstance(GKInstance goInstance, ObsoleteGoTerm obsoleteGoTerm, GKInstance replacementGoInstance)
        throws Exception {
        if (obsoleteGoTerm.hasReplacedBy()) {
            if (replacementGoInstance != null) {
                pointAllReferrersToOtherInstance(goInstance, replacementGoInstance);
            }

            adaptor.deleteInstance(goInstance);
        } else if (!hasNonGoReferrers(goInstance)) {
            // But... we still need to clear GO Entity *references* to this.goInstance before deleting THIS
            // instance.
            this.clearAttributesFromReferringGOEntities(goInstance);
            adaptor.deleteInstance(goInstance);
        } else {
            logger.info("GO:{} ({}) is marked as obsolete but there is no replacement value specified! " +
                    "Instance will *NOT* be deleted, as manual clean-up may be necessary.",
                getAccession(goInstance), goInstance.toString());
        }
    }


    public void deleteSecondaryGOInstance(GKInstance altGoInst, GKInstance primaryGOTerm) {
        try {
            pointAllReferrersToOtherInstance(altGoInst, primaryGOTerm);

            adaptor.deleteInstance(altGoInst);
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
    public Map<GKInstance, Collection<GKInstance>> deleteFlaggedInstances(
        List<GKInstance> instancesForDeletion,
        ObsoleteGoTerm obsoleteGoTerm,
        GKInstance replacementGoInstance
    ) throws Exception {
        Map<GKInstance, Collection<GKInstance>> undeletable = new HashMap<>();

        for (GKInstance instanceForDeletion : instancesForDeletion) {
            if (!isGoTermDeleteable(instanceForDeletion)) {
                undeletable.put(instanceForDeletion, getReferrersForGoTerm(instanceForDeletion) );
                continue;
            }

            obsoleteAccessionReport.printObsoleteAccessionRecord(
                instanceForDeletion,
                getAction(instanceForDeletion),
                obsoleteGoTerm.getReplacedByOrConsiderString()
            );
            deleteGoInstance(instanceForDeletion, obsoleteGoTerm, replacementGoInstance);

        }
        return undeletable;
    }

    public void logUndeletableInstances(Map<GKInstance, Collection<GKInstance>> undeletableInstanceToReferrers) throws Exception {
        for (GKInstance instance : undeletableInstanceToReferrers.keySet()) {
            obsoleteAccessionLogger.info("GO:{} ({}) could not be deleted because it had {} referrers: ",
                instance.getAttributeValue(ReactomeJavaConstants.accession),
                instance.toString(),
                undeletableInstanceToReferrers.get(instance).size()
            );
            for (GKInstance referrer : undeletableInstanceToReferrers.get(instance)) {
                GKInstance created = (GKInstance) referrer.getAttributeValue(ReactomeJavaConstants.created);
                GKInstance author = (GKInstance) created.getAttributeValue(ReactomeJavaConstants.author);
                obsoleteAccessionLogger.info("\t\"{}\", created by {} @ {}",
                    referrer.toString(),
                    author != null ? author.getDisplayName(): "author not found",
                    created.getAttributeValue(ReactomeJavaConstants.dateTime)
                );
            }
        }
    }

    private void pointAllReferrersToOtherInstance(GKInstance originalGOInstance, GKInstance replacementGOInstance) throws Exception {
        @SuppressWarnings("unchecked")
        Collection<GKSchemaAttribute> attributes =
            (Collection<GKSchemaAttribute>) originalGOInstance.getSchemClass().getReferers();
        for (GKSchemaAttribute attribute : attributes) {
            GKInstance currentReferrer = null;
            String attributeName = attribute.getName();
            try {
                @SuppressWarnings("unchecked")
                Set<GKInstance> referrers = (Set<GKInstance>) originalGOInstance.getReferers(attribute);
                if (referrers != null) {
                    for (GKInstance referrer : referrers) {
                        currentReferrer = referrer;
                        // the referrer could refer to many things via the attribute.
                        // we should ONLY remove *this* GO instance that will probably be deleted
                        // and add the replacement GO term. All other values should be left alone.
                        if (referrer.getSchemClass().isValidAttribute(attributeName)) {
                            @SuppressWarnings("unchecked")
                            List<GKInstance> referrerAttributeValues =
                                (List<GKInstance>) referrer.getAttributeValuesList(attributeName);
                            // remove *this* goInstance from the referrer
                            referrerAttributeValues = referrerAttributeValues.parallelStream()
                                .filter(v -> !v.getDBID().equals(originalGOInstance.getDBID()))
                                .collect(Collectors.toList());
                            // add the replacement to the referrer
                            if (attribute.isMultiple()) {
                                referrerAttributeValues.add(replacementGOInstance);
                                referrer.setAttributeValue(attributeName, referrerAttributeValues);
                            } else {
                                referrer.setAttributeValue(attributeName, replacementGOInstance);
                            }
                            // The old Perl code would update referrers' displayNames if they were
                            // PhysicalEntities or CatalystActivities.
                            if (referrer.getSchemClass().isa(ReactomeJavaConstants.PhysicalEntity) ||
                                referrer.getSchemClass().isa(ReactomeJavaConstants.CatalystActivity)) {
                                String newReferrerDisplayName =
                                    InstanceDisplayNameGenerator.generateDisplayName(referrer);
                                referrer.setAttributeValue(ReactomeJavaConstants._displayName, newReferrerDisplayName);
                                adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants._displayName);
                            }
                            GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
                                GoUpdateInstanceEditUtils.GOUpdateInstEditType.REF_ATTRIB_UPDATE, this.getClass());
                            referrer.getAttributeValuesList(ReactomeJavaConstants.modified);
                            referrer.addAttributeValue(ReactomeJavaConstants.modified, instEd);
                            // update in db.
                            adaptor.updateInstanceAttribute(referrer, attributeName);
                            adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants.modified);
                            logger.debug("\"{}\" now refers to \"{}\" via {}, instead of referring to \"{}\"",
                                referrer.toString(),
                                replacementGOInstance.toString(),
                                attributeName,
                                originalGOInstance.toString()
                            );
                        } else {
                            logger.error("Sorry, but the attribute \"{}\" is not valid for the referrer \"{}\". " +
                                    "This happened while trying to make \"{}\" refer to \"{}\", instead of currently " +
                                    "referring to \"GO ID: {}; {}\"",
                                attributeName,
                                abbreviate(referrer.toString()),
                                abbreviate(referrer.toString()),
                                abbreviate(replacementGOInstance.toString()),
                                originalGOInstance.getAttributeValue(ReactomeJavaConstants.accession),
                                abbreviate(originalGOInstance.toString())
                            );
                        }
                    }
                }
            } catch (InvalidAttributeException e) {
                logger.error("Invalid Attribute Error: {}; Attribute was: \"{}\"; GO instance being processed was: " +
                        "\"{}\"; Referrer was: \"{}\"",
                    e.getMessage(),
                    attribute.toString(),
                    originalGOInstance.toString(),
                    currentReferrer != null ? currentReferrer.toString() : "NULL"
                );
                OutputStream out = new ByteArrayOutputStream();
                PrintStream s = new PrintStream(out);
                e.printStackTrace(s);
                logger.error(out.toString());
            }
        }
    }


    /**
     * If a GO Term has certain referrers, it is not deletable. The rules (from Peter D.) are:<br/><br/><br/>
     * IF an GO biological process term has NOT been used as a goBiologicalProcess slot value for any event instance in
     * gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
     * IF a GO cellular component term has NOT been used as a compartment slot value for any physical entity or event
     * instance in gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
     * IF a GO molecular function term has NOT been used as the activity slot value for any catalystActivity instance
     * in gk_central, the obsolete GO term instance can be deleted from gk_central.
     * @param instance - an instance to check.
     * @return true or false, if <code>instance</code> is deleteable, as per the above rules.
     * @throws Exception
     */
    private boolean isGoTermDeleteable(GKInstance instance) throws Exception {

        Collection<GKInstance> referrers = getReferrersForGoTerm(instance);

        return referrers == null || referrers.isEmpty();
    }

    /*
     * Clears reference attributes that point TO *this* goInstance FROM other GO entities. To be used when an
     * instance is being deleted.
     */
    private void clearAttributesFromReferringGOEntities(GKInstance originalGOInstance) throws Exception {
        Map<GKSchemaAttribute, Integer> goReferrerCounts =
            getReferrerCountsFilteredByClass(originalGOInstance, isNotGOEntity.negate());
        for (GKSchemaAttribute attrib : goReferrerCounts.keySet()) {
            // set the referring attributes to NULL so that we don't end up with "dangling pointers" in the database.
            Collection<GKInstance> attribReferrers = (Collection<GKInstance>) originalGOInstance.getReferers(attrib);
            for (GKInstance attribReferrer : attribReferrers) {
                // From a few tests, it seems that 55 is a good target length to abbreviate to.
                final int abbrevLength = 55;
                try {
                    logger.info("CLEARING the attribute {} on \"{}\" because it refers to" +
                            " \"{}\", which is flagged for deletion.",
                        attrib.getName(),
                        abbreviate(attribReferrer.toString(), abbrevLength),
                        abbreviate(originalGOInstance.toString(), abbrevLength)
                    );
                    // if the attribute is multi-valued, we need to be a little more careful and remove *this*
                    // instance from the list, but not affect other items in the list.
                    if (attrib.isMultiple()) {
                        List<GKInstance> refVals = attribReferrer.getAttributeValuesList(attrib.getName());
                        int i = 0;
                        boolean done = false;
                        while (!done && i < refVals.size()) {
                            GKInstance refVal = refVals.get(i);
                            // Using DB_ID match for equality test. There is a compare method in InstanceUtilities,
                            // but it looks much deeper into the objects than I think is necessary in this case.
                            // I can't think of a situation where two objects are different despite having the same
                            // DB_ID!
                            if (refVal.getDBID().equals(originalGOInstance.getDBID())) {
                                refVals.remove(refVal);
                                done = true;
                            }
                            i++;
                        }
                        // SET the attribute to the list, which has had the offending object removed from it.
                        attribReferrer.setAttributeValue(attrib.getName(), refVals);
                        this.adaptor.updateInstanceAttribute(attribReferrer, attrib.getName());
                    } else {
                        attribReferrer.setAttributeValue(attrib.getName(), null);
                        this.adaptor.updateInstanceAttribute(attribReferrer, attrib.getName());
                    }
                    // now that the references to *this* GO Instance have been removed, record this operation by
                    // adding a "modified" InstanceEdit.
                    GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
                        GoUpdateInstanceEditUtils.GOUpdateInstEditType.REF_CLEARED, this.getClass());
                    attribReferrer.getAttributeValuesList(ReactomeJavaConstants.modified);
                    attribReferrer.addAttributeValue(ReactomeJavaConstants.modified, instEd);
                    this.adaptor.updateInstanceAttribute(attribReferrer, ReactomeJavaConstants.modified);
                } catch (Exception  e) {
                    logger.error("Error trying to clear {} attribute on \"{}\", referring to \"{}\" " +
                            "(which is to be deleted).",
                        attrib.getName(),
                        abbreviate(attribReferrer.toString(), abbrevLength),
                        abbreviate(originalGOInstance.toString(), abbrevLength)
                    );
                    e.printStackTrace();
                }
            }
        }
    }


    /**
     * Gets a collection of GKInstances the refer to a Go Term.
     * @param instance - the instance to get referrers for.
     * @return A collection:<br/>
     * If the instance is a BiologicalProcess,
     * all instances that refer to it via goBiologicalProcess will be returned.<br/>
     * If the instances is a CellularComponent then all instances that refer via compartment will be returned.<br/>
     * If the instance is a MolecularFunction, all instances that refer via activity will be returned.<br/>
     * NULL will be returned if there are no referrers.
     * @throws Exception
     */
    private Collection<GKInstance> getReferrersForGoTerm(GKInstance instance) throws Exception {
        Collection<GKInstance> referrers = null;

        SchemaClass instanceSchemaClass = instance.getSchemClass();
        if (instanceSchemaClass.isa(ReactomeJavaConstants.GO_BiologicalProcess)) {
            referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.goBiologicalProcess);

        } else if (instanceSchemaClass.isa(ReactomeJavaConstants.GO_CellularComponent)) {
            referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.compartment);

        } else if (instanceSchemaClass.isa(ReactomeJavaConstants.GO_MolecularFunction)) {
            referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.activity);
        }

        return referrers;
    }

    private String getAction(GKInstance instance) throws Exception {
        return hasNonGoReferrers(instance) ?
            "Automatic Deletion (referrers will be redirected)" :
            "Automatic Deletion (no referrers)";
    }
}
