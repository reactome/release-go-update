package org.reactome.release.goupdate.utils;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.CuratorToolWsApplication;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 7/5/2026
 */
public class CuratorToolAPI {

    private static final Logger logger = LoggerFactory.getLogger(CuratorToolAPI.class);

    private static final List<String> GO_CLASS_NAMES = Arrays.asList(
        ReactomeJavaConstants.GO_BiologicalProcess,
        ReactomeJavaConstants.GO_CellularComponent,
        ReactomeJavaConstants.GO_MolecularFunction
    );

    private static CurationController controller;
    private long personId;

    // The GO instances are loaded once, up-front, and kept in memory for the whole run. The server rejects a
    // commit whose "modified" InstanceEdit does not match the stored one (optimistic locking) and a commit
    // replaces *all* of the stored instance's attributes, so an in-memory copy must be re-read once this run has
    // written to it. These sets record what this run has written to and what it has removed.
    private final Set<Long> committedDbIds = new HashSet<>();
    private final Set<Long> deletedDbIds = new HashSet<>();

    // Source of the negative placeholder dbIds given to instances that are not in the database yet.
    private final AtomicLong placeholderDbIdCounter = new AtomicLong();

    private ConfigurableApplicationContext applicationContext;

    public CuratorToolAPI(long personId) {
        if (controller == null) {
            controller = this.initController();
            if (controller == null) {
                throw new IllegalStateException("Failed to initialize CuratorToolAPI: controller is null");
            }
        }
        this.personId = personId;
    }

    // The following code is copied directly from the slicing tool project.
    private CurationController initController() {
        try {
            // curator-tool-ws's bundled application.properties forces DEBUG for these loggers and binds the
            // HTTP connector to 9090. System properties outrank a classpath application.properties in Spring
            // Boot's precedence order, so these settings take hold for the batch run without editing
            // curator-tool-ws. (SpringApplicationBuilder.properties(...) are default/lowest precedence and
            // would NOT override application.properties.)
            System.setProperty("logging.level.org.springframework.data.neo4j", "WARN");
            System.setProperty("logging.level.org.springframework.security", "WARN");
            // Disable the HTTP server; the full servlet context is kept for correct AspectJ wiring.
            System.setProperty("server.port", "-1");

            applicationContext = new SpringApplicationBuilder(CuratorToolWsApplication.class)
                .web(WebApplicationType.SERVLET)
                .run();
            return applicationContext.getBean(CurationController.class);
        }
        catch (Exception e) {
            logger.error("GraphDBInstanceManager.initController(): " + e.getMessage(), e);
        }
        return null;
    }

    public SimpleInstance commit(SimpleInstance simpleInstance) {
        if (simpleInstance.getDefaultPersonId() == null) {
            simpleInstance.setDefaultPersonId(getPersonId());
        }

        boolean isNewInstance = simpleInstance.getDbId() == null || simpleInstance.getDbId() < 0;
        if (simpleInstance.getDbId() == null) {
            simpleInstance.setDbId(nextPlaceholderDbId());
        }

        SimpleInstance committedInstance = controller.commit(simpleInstance);

        if (isNewInstance && committedInstance != null) {
            // The response carries the dbId the database assigned in place of the placeholder, and it is the only
            // place it is reported, so it is copied back onto the instance the caller holds.
            simpleInstance.setDbId(committedInstance.getDbId());
        }

        Long dbId = simpleInstance.getDbId();
        if (dbId != null && dbId > 0) {
            committedDbIds.add(dbId);
            deletedDbIds.remove(dbId);
        }
        return committedInstance;
    }

    /**
     * Returns a placeholder dbId for an instance that is not in the database yet. curator-tool-ws identifies a new
     * instance by a NEGATIVE dbId: it is what makes a commit store the instance (recording the author in its
     * "created" slot rather than in "modified") and replace the placeholder with a real dbId. A null dbId is not a
     * substitute -- curator-tool-ws unboxes the dbId without a null check while working out what to store
     * (DatabaseObjectInstanceConverter.convert and CurationService.grepNewInstances), so committing an instance
     * with a null dbId fails with a NullPointerException.
     *
     * @return a negative dbId, unused by any other instance created during this run.
     */
    private long nextPlaceholderDbId() {
        return -placeholderDbIdCounter.incrementAndGet();
    }

    /**
     * Returns a copy of the instance that is up to date with the database if this run has already committed it,
     * and the instance itself otherwise. Returns null if this run has deleted the instance, in which case the
     * caller must stop using it -- committing it would re-create the deleted instance.
     *
     * An instance that this run has committed is stale in two ways: its "modified" InstanceEdit no longer matches
     * the stored one, so a further commit is rejected with an InstanceChangedException, and any attribute written
     * by that commit (or by a commit of a fresh copy of the same instance elsewhere in the run) still holds its
     * pre-commit value, which the next commit would write back over the stored value.
     *
     * @param instance - the instance to refresh.
     * @return an up-to-date instance, or null if the instance has been deleted by this run.
     */
    public SimpleInstance refresh(SimpleInstance instance) {
        Long dbId = instance.getDbId();
        if (dbId == null) {
            return instance; // Never committed, so there is nothing stored to be out of date with.
        }
        if (deletedDbIds.contains(dbId)) {
            return null;
        }
        return committedDbIds.contains(dbId) ? inflate(instance) : instance;
    }

    public List<SimpleInstance> fetchGOInstances() {
        List<SimpleInstance> goShellInstances = new ArrayList<>();
        for (String goClassName : GO_CLASS_NAMES) {
            goShellInstances.addAll(fetchInstancesForClass(goClassName));
        }

        return goShellInstances.parallelStream().map(this::inflate).collect(Collectors.toList());
    }

    /**
     * Returns every GO instance in the database, keyed by its GO accession.
     *
     * Reading them costs a query per instance, so a caller that needs this more than once for the same state
     * of the database should hold on to the result rather than asking again.
     *
     * @return the GO instances by accession. An accession with more than one instance -- a duplicate -- maps
     *         to all of them.
     */
    public Map<String, List<SimpleInstance>> fetchGOInstancesByAccession() {
        logger.info("Reading GO instances from the database...");

        Map<String, List<SimpleInstance>> goInstancesByAccession = fetchGOInstances()
            .stream()
            .collect(Collectors.groupingBy(Utils::getAccession));

        logger.info("Read GO instances for {} GO accessions.", goInstancesByAccession.size());

        return goInstancesByAccession;
    }

    public SimpleInstance findByDbId(long dbId) {
        DatabaseObject databaseObject = controller.findByDdId(dbId);
        if (databaseObject == null) {
            return null;
        }

        try {
            return controller.getConverter().convert(databaseObject);
        } catch (Exception e) {
            throw new RuntimeException("Unable to convert DatabaseObject " + databaseObject + " to SimpleInstance", e);
        }
    }

    public SimpleInstance findByDisplayName(String className, String displayName) {
        // NB: the controller takes the display name first and a comma-separated list of class names second.
        return controller.findByDisplayName(displayName, className);
    }

    public SimpleInstance fetchGOReferenceDatabase() {
        SimpleInstance goReferenceDatabase = findByDisplayName(ReactomeJavaConstants.ReferenceDatabase, "GO");
        if (goReferenceDatabase == null) {
            throw new IllegalStateException("No " + ReactomeJavaConstants.ReferenceDatabase +
                " instance with the display name 'GO' exists in the database");
        }
        return goReferenceDatabase;
    }

    public void deleteInstance(SimpleInstance instance) {
        controller.delete(instance);
        if (instance.getDbId() != null) {
            deletedDbIds.add(instance.getDbId());
            committedDbIds.remove(instance.getDbId());
        }
    }

    public void close() {
        applicationContext.close();
    }

    public SimpleInstance inflate(SimpleInstance shellInstance) {
        return controller.findByDdIdInInstance(shellInstance.getDbId());
    }

    public List<SimpleInstance> getReferrers(SimpleInstance instance, String referrerAttributeName) throws Exception {
        return controller.getReferrers(instance.getDbId())
            .stream()
            .filter(g -> referrerAttributeName.equals(g.getAttributeName()))
            .findFirst()
            .map(NamedReferrerList::getReferrers)
            .orElse(Collections.emptyList());
    }

    public Collection<NamedReferrerList> getReferrers(SimpleInstance instance) throws Exception {
        return controller.getReferrers(instance.getDbId());
    }

    public long getPersonId() {
        return this.personId;
    }

    private List<SimpleInstance> fetchInstancesForClass(String className) {
        List<SimpleInstance> instances = new ArrayList<>();

        int pageSize = 500;
        int skip = 0;
        Integer total = null;

        do {
            InstanceList page = controller.listInstances(className, skip, pageSize, Optional.empty());

            if (total == null) {
                total = page.getTotalCount();   // set once from the first page
            }
            instances.addAll(page.getInstances());
            skip += pageSize;
        } while (skip < total);

        return instances;
    }
}
