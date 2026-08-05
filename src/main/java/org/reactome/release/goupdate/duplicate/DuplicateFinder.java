package org.reactome.release.goupdate.duplicate;

import static java.util.stream.Collectors.groupingBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.utils.CuratorToolAPI;
import org.reactome.release.goupdate.utils.Utils;

/**
 * This class reports on duplicate GO Accessions.
 * A GO Accession is considered duplicated if more than one
 * GO_MolecularFunction/GO_BiologicalProcess/GO_CellularComponent has the same accession value.
 *
 * @author sshorser
 */
public class DuplicateFinder {

	private CuratorToolAPI curatorToolAPI;

	// The GO instances in the database keyed by GO accession, read on first use and kept for the lifetime of
	// this finder. The instances of a duplicated accession are then taken from here rather than queried for
	// again, once per GO class, for every duplicate found -- and both calls belong to the same report, so they
	// need to see the same instances in any case.
	private Map<String, List<SimpleInstance>> accessionToGOInstances;

	public DuplicateFinder(CuratorToolAPI curatorToolAPI) {
		this.curatorToolAPI = curatorToolAPI;
	}

	/**
	 * Gets the duplicated accessions.
	 * @return A map of accessions, and number of times they appear in the database.
	 */
	public Map<String, Integer> getDuplicateAccessions() {
		return getAccessionToGOInstances()
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

		for (SimpleInstance goInstance : getInstancesByAccession(accession)) {
			referrerCounts.put(goInstance.getDbId(), getReferrerCountForInstance(goInstance, classesToIgnore));
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
	private int getReferrerCountForInstance(SimpleInstance instance, String ...classesToIgnore) throws Exception {
		int refCount = 0;

		for (NamedReferrerList referrerAttribute : getCuratorToolAPI().getReferrers(instance)) {
			List<SimpleInstance> referrers = referrerAttribute.getReferrers();
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

	private List<SimpleInstance> getInstancesByAccession(String accession) {
		return getAccessionToGOInstances().getOrDefault(accession, Collections.emptyList());
	}

	private Map<String, List<SimpleInstance>> getAccessionToGOInstances() {
		if (this.accessionToGOInstances == null) {
			this.accessionToGOInstances = getCuratorToolAPI().fetchGOInstances()
				.stream()
				.collect(
					groupingBy(Utils::getAccession)
				);
		}

		return this.accessionToGOInstances;
	}

	private boolean shouldIncludeReferrer(SimpleInstance referrer, String ...classesToIgnore) {
		String referrerSchemaClass = referrer.getSchemaClassName();
		return !Arrays.asList(classesToIgnore).contains(referrerSchemaClass);
	}

	private CuratorToolAPI getCuratorToolAPI() {
		return this.curatorToolAPI;
	}
}
