package org.reactome.release.goupdate.duplicate;

import static java.util.stream.Collectors.groupingBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;

/**
 * This class reports on duplicate GO Accessions.
 * A GO Accession is considered duplicated if more than one
 * GO_MolecularFunction/GO_BiologicalProcess/GO_CellularComponent has the same accession value.
 *
 * @author sshorser
 */
public class DuplicateFinder {

	private MySQLAdaptor adaptor;

	public DuplicateFinder(MySQLAdaptor adaptor) {
		this.adaptor = adaptor;
	}

	/**
	 * Gets the duplicated accessions.
	 * @return A map of accessions, and number of times they appear in the database.
	 * @throws SQLException
	 */
	public Map<String, Integer> getDuplicateAccessions() throws Exception {
		List<GKInstance> goInstances = new ArrayList<>();
		goInstances.addAll(this.adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess));
		goInstances.addAll(this.adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction));
		goInstances.addAll(this.adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent));

		Map<String, Integer> accessionToDuplicateGoInstanceCount = goInstances
			.stream()
			.collect(
				groupingBy(this::getAccession)
			)// Map of accession to list of GO Instances (GKInstance objects)
			.entrySet()
			.stream()
				// Filter to allow only duplicated accessions (many GO instances)
			.filter(entry -> entry.getValue().size() > 1)
			.collect(
				Collectors.toMap(
					Map.Entry::getKey,
					entry -> entry.getValue().size()
				)
			);

		return accessionToDuplicateGoInstanceCount;
	}

	private String getAccession(GKInstance goInstance) {
		try {
			String accession = (String) goInstance.getAttributeValue(ReactomeJavaConstants.accession);
			return accession != null ? accession : "";
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Gets the number of referrers for each instance of a duplicated accession.
	 * @param accession - The accession to look up.
	 * @param classesToIgnore - A list of class names to ignore, when looking for referrers. Any referrer whose
	 *                          Schema Class is in classes to ignore will not be considered as a referrer, and will not
	 *                          be added to the count.
	 * @return The DB_IDs of the duplicated accession mapping to the number of referrers of each one.
	 * @throws Exception
	 */
	
	public Map<Long, Integer> getReferrerCountForAccession(String accession, String ...classesToIgnore)
		throws Exception {

		Map<Long, Integer> referrerCounts = new HashMap<>();

		// We'll have to do this for BiologicalProcess, for MolecularFunction, and for CellularComponent
		for (String goInstanceClassName : getGOInstanceClassNames()) {
			Collection<GKInstance> goInstances = getInstancesByAccession(goInstanceClassName, accession);

			for (GKInstance goInstance : goInstances) {
				long dbId = goInstance.getDBID();
				int refCount = getReferrerCountForInstance(goInstance, classesToIgnore);
				referrerCounts.put(dbId, refCount);
			}

		}
		return referrerCounts;
	}

	// TODO: Move this function to release-common-lib, maybe. It's a generic utility function, could be useful
	//  somewhere else.
	/**
	 * Gets the number of referrers for an instance. That is, it returns how many objects refer to
	 * <code>instance</code>.
	 * @param instance - the object to query about.
	 * @param classesToIgnore - a list of class names to ignore. Referrers whose SchemaClass name match a name in this
	 *                          list will *not* be included in the final count.
	 * @return Referrer count
	 * @throws Exception Thrown if unable to get referrers for an instance attribute
	 */
	private int getReferrerCountForInstance(GKInstance instance, String ...classesToIgnore) throws Exception {
		int refCount = 0;

		for (GKSchemaAttribute referrerAttribute : getReferrerAttributes(instance)) {
			@SuppressWarnings("unchecked")
			Collection<GKInstance> referrers = (Collection<GKInstance>) instance.getReferers(referrerAttribute);
			if (classesToIgnore != null && classesToIgnore.length > 0) {
				// filter the referrers: we will collect all Referrers into a new list, IF their Class is not in the
				// list of classes to ignore.
				referrers = referrers.stream()
					.filter(referrer -> shouldIncludeReferrer(referrer, classesToIgnore))
					.collect(Collectors.toList());
			}
			refCount += referrers.size();
		}

		return refCount;
	}

	@SuppressWarnings("unchecked")
	private Collection<GKInstance> getInstancesByAccession(String goInstanceClassName, String accession)
		throws Exception {

		Collection<GKInstance> instancesForAccession = (Collection<GKInstance>) this.adaptor.fetchInstanceByAttribute(
			goInstanceClassName,
			ReactomeJavaConstants.accession,
			accession != null ? "=" : "IS NULL",
			accession
		);

		return instancesForAccession != null ? instancesForAccession : new ArrayList<>();
	}

	private List<String> getGOInstanceClassNames() {
		return Arrays.asList(
			ReactomeJavaConstants.GO_BiologicalProcess,
			ReactomeJavaConstants.GO_MolecularFunction,
			ReactomeJavaConstants.GO_CellularComponent
		);
	}

	private boolean shouldIncludeReferrer(GKInstance referrer, String ...classesToIgnore) {
		String referrerSchemaClass = referrer.getSchemClass().getName();
		return !Arrays.asList(classesToIgnore).contains(referrerSchemaClass);
	}

	@SuppressWarnings("unchecked")
	private Set<GKSchemaAttribute> getReferrerAttributes(GKInstance instance) {
		return (Set<GKSchemaAttribute>) instance.getSchemClass().getReferers();
	}
}
