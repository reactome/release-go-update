package org.reactome.release.goupdate.utils;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.schema.GKSchemaAttribute;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Utils {
    public static Predicate<GKInstance> isNotGOEntity =
        i -> !i.getSchemClass().isa(ReactomeJavaConstants.GO_MolecularFunction)
            && !i.getSchemClass().isa(ReactomeJavaConstants.GO_BiologicalProcess)
            && !i.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent);

    public static boolean hasNonGoReferrers(GKInstance goInstance) throws Exception {
        return !getReferrerCountsExcludingGOEntities(goInstance).isEmpty();
    }

    /**
     * Gets referrer counts, where the Schema Class of the referrers are filtered by a user-supplied predicate.
     * @param inst - the Instance to get referrer counts for.
     * @param classFilter - A Predicate. This predicate will be used to filter the classes of the referrers.
     * @return A map whose key is the attrbite that referrs to <code>inst</code>, and the value is the *number* of
     * referrers that refer to <code>inst</code> via that attribute.
     * @throws Exception
     */
    public static Map<GKSchemaAttribute, Integer> getReferrerCountsFilteredByClass(
        GKInstance inst, Predicate<? super GKInstance> classFilter) throws Exception {

        Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
        for (GKSchemaAttribute attrib : (Collection<GKSchemaAttribute>)inst.getSchemClass().getReferers()) {
            Collection<GKInstance> referrers = (Collection<GKInstance>) inst.getReferers(attrib);

            referrers = referrers.stream().filter(classFilter).collect(Collectors.toList());

            if (!referrers.isEmpty()) {
                referrersCount.put(attrib, referrers.size());
            }
        }
        return referrersCount;
    }

    public static String getAccession(GKInstance goInstance) {
        try {
            return (String) goInstance.getAttributeValue(ReactomeJavaConstants.accession);
        } catch (Exception e) {
            throw new RuntimeException("Unable to get GO accession from " + goInstance, e);
        }
    }

    public static String abbreviate(String s) {
        // 47 is used because if the input string is too long it will be shortened to 47 characters, plus 3 for "..."
        // so it will be EXACTLY 50 characters long.
        return abbreviate(s, 47);
    }

    public static String abbreviate(String s, int maxLength) {
        return s.substring(0,Math.min(s.length(), maxLength)) + ( s.length() > maxLength ? "..." : "" );
    }

    /**
     * Gets the referrer counts, but excluding Referrers that are GO entities
     * @param inst - The instance to get counts for.
     * @return A map whose key is the attribute that refers to <code>inst</code>, and the value is the *number* of
     * referrers that refer to <code>inst</code> via that attribute.
     * @throws Exception
     */
    private static Map<GKSchemaAttribute, Integer> getReferrerCountsExcludingGOEntities(GKInstance inst) throws Exception {
        return getReferrerCountsFilteredByClass(inst, isNotGOEntity);
    }
}
