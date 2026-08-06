package org.reactome.release.goupdate.utils;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.GONamespace;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Utils {
    public static Predicate<SimpleInstance> isNotGOEntity =
        i -> !i.getSchemaClassName().equals(ReactomeJavaConstants.GO_MolecularFunction)
            && !i.getSchemaClassName().equals(ReactomeJavaConstants.GO_BiologicalProcess)
            && !i.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent);

    public static boolean hasNonGoReferrers(SimpleInstance goInstance, CuratorToolAPI curatorToolAPI) throws Exception {
        return hasNonGoReferrers(curatorToolAPI.getReferrers(goInstance));
    }

    /**
     * Returns true if any of the referrers is something other than a GO entity.
     *
     * This takes the referrer lists rather than reading them, so that a caller which needs them for more than
     * one decision about the same instance can read them once and ask this more than once.
     *
     * @param referrerLists - the referrers of an instance, by the attribute they refer to it through.
     * @return true if at least one referrer is not a GO entity, false otherwise.
     */
    public static boolean hasNonGoReferrers(Collection<NamedReferrerList> referrerLists) {
        // Short-circuits on the first non-GO referrer instead of collecting all of them to ask whether any
        // were found.
        return referrerLists
            .stream()
            .flatMap(namedReferrerList -> namedReferrerList.getReferrers().stream())
            .anyMatch(isNotGOEntity);
    }

    public static List<SimpleInstance> getReferrersFilteredByClass(
        SimpleInstance inst, CuratorToolAPI curatorToolAPI, Predicate<? super SimpleInstance> classFilter)
        throws Exception {

        List<SimpleInstance> referrers= new ArrayList<>();
        for (NamedReferrerList namedReferrerList : curatorToolAPI.getReferrers(inst)) {
            for (SimpleInstance referrer : namedReferrerList.getReferrers()) {
                if (classFilter.test(referrer)) {
                    referrers.add(referrer);
                }
            }
        }

        return referrers;
    }

    /**
     * Returns true if the instance is a GO entity, i.e., a GO_MolecularFunction, GO_BiologicalProcess or
     * GO_CellularComponent (including the GO_CellularComponent subclasses Compartment and EntityCompartment).
     * @param instance - the instance to check.
     * @return true if <code>instance</code> is a GO entity, false otherwise.
     */
    public static boolean isGOEntity(SimpleInstance instance) {
        String schemaClassName = instance.getSchemaClassName();
        return schemaClassName.equals(ReactomeJavaConstants.GO_MolecularFunction)
            || schemaClassName.equals(ReactomeJavaConstants.GO_BiologicalProcess)
            || schemaClassName.equals(ReactomeJavaConstants.GO_CellularComponent)
            || schemaClassName.equals(ReactomeJavaConstants.Compartment)
            || schemaClassName.equals(ReactomeJavaConstants.EntityCompartment);
    }

    /**
     * Returns true if the instance's schema class is the class that a GO namespace maps to, or a subclass of it.
     * Compartment and EntityCompartment are subclasses of GO_CellularComponent, so an instance of either is a
     * match for a cellular_component term; the other two namespaces have no subclasses to allow for.
     *
     * @param goInstance - the instance whose schema class to check.
     * @param namespace - the namespace from the GO file.
     * @return true if <code>goInstance</code> has a schema class that <code>namespace</code> allows, false
     *         otherwise.
     */
    public static boolean hasClassForNamespace(SimpleInstance goInstance, GONamespace namespace) {
        String schemaClassName = goInstance.getSchemaClassName();
        String namespaceClassName = namespace.getReactomeName();

        if (schemaClassName.equals(namespaceClassName)) {
            return true;
        }

        return namespaceClassName.equals(ReactomeJavaConstants.GO_CellularComponent)
            && (schemaClassName.equals(ReactomeJavaConstants.Compartment)
                || schemaClassName.equals(ReactomeJavaConstants.EntityCompartment));
    }

    public static String getAccession(SimpleInstance goInstance) {
        try {
            String accession = (String) goInstance.getAttribute(ReactomeJavaConstants.identifier);
            return accession != null ? accession : "";
        } catch (Exception e) {
            throw new RuntimeException("Unable to get GO accession from " + goInstance, e);
        }
    }

    /**
     * Clears an attribute value on an instance by removing the attribute from the instance altogether.
     *
     * SimpleInstance.setAttribute(name, null) must not be used for this: it leaves the attribute name in the
     * instance's attribute map with a null value, and curator-tool-ws's SimpleInstance to DatabaseObject
     * converter throws a NullPointerException on a null attribute value. Removing the attribute clears the
     * value in the database all the same, since committing an existing instance deletes all of its attribute
     * relationships and properties before re-storing the converted DatabaseObject.
     *
     * @param instance - the instance on which to clear the attribute.
     * @param attributeName - the name of the attribute to clear.
     */
    public static void clearAttribute(SimpleInstance instance, String attributeName) {
        if (instance.getAttributes() != null) {
            instance.getAttributes().remove(attributeName);
        }
    }

    /**
     * Brings a list of GO instances held in memory back in line with the database: each instance this run has
     * already committed is replaced by a freshly read copy and each instance this run has deleted is removed from
     * the list. The list is modified in place, so callers holding the list found in the map of all GO instances
     * keep that map up to date too.
     *
     * @param instances - the instances to refresh.
     * @param curatorToolAPI - the API to read the instances with.
     */
    public static void refreshInstances(List<SimpleInstance> instances, CuratorToolAPI curatorToolAPI) {
        ListIterator<SimpleInstance> instanceIterator = instances.listIterator();
        while (instanceIterator.hasNext()) {
            SimpleInstance refreshedInstance = curatorToolAPI.refresh(instanceIterator.next());
            if (refreshedInstance == null) {
                instanceIterator.remove();
            } else {
                instanceIterator.set(refreshedInstance);
            }
        }
    }

    /**
     * Returns a copy of an instance carrying only what a commit needs in order to refer to it: its dbId, schema
     * class and displayName.
     *
     * An instance-valued attribute must be given one of these rather than the instance itself whenever the value
     * is already in the database. curator-tool-ws walks the whole object graph of the instance being committed
     * looking for new instances to store (DatabaseObjectInstanceConverter.grepNewInstanceDbIds), and that walk
     * only marks an instance as visited *after* recursing into it, so a cycle in the graph makes it recurse until
     * the stack runs out. The GO relationships are cyclic: twelve pairs of cellular_component terms name each
     * other through part_of/has_part, which becomes a cycle between two in-memory instances as soon as both
     * terms have had their relationships set from the file. Storing the relationship uses nothing but the dbId
     * of an already-stored value, so dropping the rest costs nothing.
     *
     * An instance that is not in the database yet -- no dbId, or the negative placeholder one -- is returned
     * unchanged: the commit has to store it, and for that it needs its attributes.
     *
     * @param instance - the instance to make a shell of.
     * @return a shell of the instance, or the instance itself if it is not in the database yet.
     */
    public static SimpleInstance toShell(SimpleInstance instance) {
        Long dbId = instance.getDbId();
        if (dbId == null || dbId < 0) {
            return instance;
        }

        SimpleInstance shellInstance = new SimpleInstance();
        shellInstance.setDbId(dbId);
        shellInstance.setSchemaClassName(instance.getSchemaClassName());
        shellInstance.setDisplayName(instance.getDisplayName());
        return shellInstance;
    }

    /**
     * Returns shells of a list of instances, in the same order.
     *
     * @param instances - the instances to make shells of.
     * @return the shells of the instances.
     * @see #toShell(SimpleInstance)
     */
    public static List<SimpleInstance> toShells(List<SimpleInstance> instances) {
        return instances.stream().map(Utils::toShell).collect(Collectors.toList());
    }

    public static String abbreviate(String s, int maxLength) {
        return s.substring(0,Math.min(s.length(), maxLength)) + ( s.length() > maxLength ? "..." : "" );
    }
}
