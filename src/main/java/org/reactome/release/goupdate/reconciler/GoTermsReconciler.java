package org.reactome.release.goupdate.reconciler;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.GONamespace;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.parser.GoTermParser;
import org.reactome.release.goupdate.utils.Utils;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;
import static org.reactome.release.goupdate.utils.Utils.getAccession;
import static org.reactome.release.goupdate.utils.Utils.hasClassForNamespace;

/**
 * This class should be used to reconcile between the GO file and the database, after updates have been attempted.
 * @author sshorser
 *
 */
public class GoTermsReconciler {
	private static final String IS_A = "is_a";
	private static final String HAS_PART = "has_part";
	private static final String PART_OF = "part_of";

	private static final Logger logger = LogManager.getLogger();
	private static final Logger reconciliationLogger = LogManager.getLogger("reconciliationLog");

	// The GO instances in the database, keyed by GO accession. Reconciliation runs after the update has
	// finished, so nothing changes these instances while it is working, and a lookup in this map takes the
	// place of a database query for every term in the GO file.
	private final Map<String, List<SimpleInstance>> accessionToGOInstances;

	// The GO accession of each of those instances, by dbId. A relationship value arrives as a shell instance
	// that carries no attributes, so its accession is resolved through this map rather than by reading the
	// instance back from the database.
	private final Map<Long, String> dbIdToAccession;

	/**
	 * Creates a reconciler for a set of GO instances.
	 *
	 * @param accessionToGOInstances - the GO instances in the database keyed by GO accession, as they stand
	 *                                 after the update. Nothing may change them while reconciliation runs.
	 */
	public GoTermsReconciler(Map<String, List<SimpleInstance>> accessionToGOInstances) {
		this.accessionToGOInstances = accessionToGOInstances;
		this.dbIdToAccession = accessionToGOInstances.values()
			.stream()
			.flatMap(List::stream)
			.collect(Collectors.toMap(
				SimpleInstance::getDbId,
				Utils::getAccession,
				(accession, duplicateAccession) -> accession
			));
	}

	/**
	 * Attempts to reconcile between the database and the terms from the parser.
	 * Reconciliation reports are logged to a file (not returned).
	 * @param goTermParser GoTerm parser used to iterate over GO Terms
	 * @throws Exception
	 */
	public void reconcile(GoTermParser goTermParser) throws Exception {
		Iterator<? extends GoTerm> goTermIterator = goTermParser.getGoTermIterator();
		while (goTermIterator.hasNext()) {
			GoTerm goTerm = goTermIterator.next();
			List<SimpleInstance> goInstances = getInstancesForGoTerm(goTerm);
			if (goInstances.isEmpty()) {
				// It should be reported if there were no instances returned, but the term is not obsolete.
				if (!isObsolete(goTerm)) {
					reconciliationLogger.warn("GO Accession {} is not present in the database, " +
						"but is NOT marked as obsolete. GO Term might have been deleted in error, or not properly " +
						"created.", goTerm.getId()
					);
				}
				continue;
			}

			if (goInstances.size() > 1) {
				reconciliationLogger.warn("GO Accession {} appears {} times in the database. " +
					"It should probably only appear once.", goTerm.getId(), goInstances.size());
			}

			logger.debug("Reconciling GO Term {}...", goTerm.getId());
			for (SimpleInstance goInstance : goInstances) {
				reconcileDefinition(goInstance, goTerm);
				reconcileName(goInstance, goTerm);
				reconcileNamespace(goInstance, goTerm);
				reconcileIsA(goInstance, goTerm);
				reconcilePartOf(goInstance, goTerm);
				reconcileHasPart(goInstance, goTerm);
				reconcileECNumbers(goInstance, goTerm);
			}
		}
	}

	private void reconcileDefinition(SimpleInstance goInstance, GoTerm goTerm) {
		String goInstanceDefinition = (String) goInstance.getAttribute(ReactomeJavaConstants.definition);
		if (!goTerm.getDef().equals(goInstanceDefinition)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'definition';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), goTerm.getDef(), goInstanceDefinition
			);
		}
	}

	private void reconcileName(SimpleInstance goInstance, GoTerm goTerm) {
		// "name" is multi-valued in the data model. The GO file gives a term one name, which the update writes
		// as the single value, so it is the first value that is compared here.
		List<String> goInstanceNames = getStringValues(goInstance, ReactomeJavaConstants.name);
		String goInstanceName = goInstanceNames.isEmpty() ? "" : goInstanceNames.get(0);

		if (!goTerm.getName().equals(goInstanceName)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'name';\n" +
				"\tValue from file: \"{}\";\n" +
				"\tValue from database: \"{}\"",
				goTerm.getId(), goTerm.getName(), goInstanceName
			);
		}
	}

	private void reconcileNamespace(SimpleInstance goInstance, GoTerm goTerm) {
		String dbNameSpace = goInstance.getSchemaClassName();
		String fileNameSpace = goTerm.getNamespace().getReactomeName();
		if (!hasClassForNamespace(goInstance, goTerm.getNamespace())) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'namespace/SchemaClass';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), fileNameSpace, dbNameSpace
			);
		}
	}

	private void reconcileIsA(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			reconcileRelationship(
				goTerm.getId(),
				goTerm.getIsA(),
				getRelationshipInstances(goInstance, ReactomeJavaConstants.instanceOf),
				IS_A
			);
		}
	}

	private void reconcilePartOf(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			reconcileRelationship(
				goTerm.getId(),
				goTerm.getPartOf(),
				getRelationshipInstances(goInstance, ReactomeJavaConstants.componentOf),
				PART_OF
			);
		}
	}

	private void reconcileHasPart(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			reconcileRelationship(
				goTerm.getId(),
				goTerm.getHasPart(),
				getRelationshipInstances(goInstance, ReactomeJavaConstants.hasPart),
				HAS_PART
			);
		}
	}

	/**
	 * Reconciles a relationship for a GO term. Will not return, but will log an ERROR message if reconciliation fails.
	 * @param goAccession - The accession of the term to reconcile.
	 * @param goTermRelationAccessions - The relationship accessions from the GO term.
	 * @param relationInstances - The instances the corresponding database instance is related to by this
	 *                            relationship.
	 * @param relationship - The name of the relationship, as the GO file gives it.
	 */
	private void reconcileRelationship(
		String goAccession,
		List<String> goTermRelationAccessions,
		List<SimpleInstance> relationInstances,
		String relationship
	) {
		if (goTermRelationAccessions.isEmpty()) {
			return;
		}

		// Resolved once per instance rather than once per (accession, instance) pair, and out of the snapshot
		// rather than by reading each related instance back from the database.
		Set<String> accessionsFromDB = relationInstances
			.stream()
			.map(relationInstance -> this.dbIdToAccession.get(relationInstance.getDbId()))
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		for (String goTermRelationAccession : goTermRelationAccessions) {
			if (!accessionsFromDB.contains(goTermRelationAccession)) {
				reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"{}\"; " +
						"File says that GO:{} should be present but it is not in the database.",
					goAccession, relationship, goTermRelationAccession
				);
			}
		}
	}

	/**
	 * Reconciles EC Numbers for a GO term, between the data from ec2go file and the database.
	 * Logs an ERROR if EC numbers fail to reconcile.
	 * @param instance - the instance to reconcile.
	 * @param goTerm  - goTerm to compare
	 */
	private void reconcileECNumbers(SimpleInstance instance, GoTerm goTerm) {
		if (goTerm.getNamespace().getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction)) {
			// An instance with no EC number at all has no value for the attribute.
			String oldEcNumber = getECNumber(instance);

			if (!goTerm.getEcNumber().equals(oldEcNumber)) {
				reconciliationLogger.error(
					"EC Number {} is in the file for GO Accession {} but is not in the db for that accession.",
					goTerm.getEcNumber(), getAccession(instance)
				);
			}
		}
	}

	/**
	 * Returns the GO instances in the database for a GO term, filtered to the instances whose schema class the
	 * term's namespace allows.
	 *
	 * The filter is what querying the database for the namespace's own class used to do: a query by class also
	 * returns the instances of that class's subclasses, so a cellular_component term matched GO_CellularComponent,
	 * Compartment and EntityCompartment alike. An instance that carries this term's accession under a class the
	 * namespace does not allow is therefore not returned, and the term is reported as absent from the database.
	 *
	 * @param goTerm - the GO term to find the database instances of.
	 * @return the instances for the term, or an empty list if it has none.
	 */
	private List<SimpleInstance> getInstancesForGoTerm(GoTerm goTerm) {
		return this.accessionToGOInstances.getOrDefault(goTerm.getId(), Collections.emptyList())
			.stream()
			.filter(goInstance -> hasClassForNamespace(goInstance, goTerm.getNamespace()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns the instances an instance is related to by an attribute, as a list.
	 *
	 * @param instance - the instance to read the attribute of.
	 * @param attributeName - the name of the relationship attribute.
	 * @return the related instances, or an empty list if the attribute has no value.
	 */
	private List<SimpleInstance> getRelationshipInstances(SimpleInstance instance, String attributeName) {
		Object attributeValue = instance.getAttribute(attributeName);
		if (attributeValue == null) {
			return Collections.emptyList();
		}

		// These attributes are multi-valued in the data model, but an instance stored while the graph model
		// still declared one of them single-valued comes back as a lone SimpleInstance.
		return attributeValue instanceof List ?
			(List<SimpleInstance>) attributeValue :
			Collections.singletonList((SimpleInstance) attributeValue);
	}

	/**
	 * Returns the values of a multi-valued String attribute, as a list.
	 *
	 * @param instance - the instance to read the attribute of.
	 * @param attributeName - the name of the attribute.
	 * @return the attribute's values, or an empty list if it has none.
	 */
	private List<String> getStringValues(SimpleInstance instance, String attributeName) {
		Object attributeValue = instance.getAttribute(attributeName);
		if (attributeValue == null) {
			return Collections.emptyList();
		}

		// As above: an instance stored while the graph model still declared the attribute single-valued comes
		// back as a lone String.
		return attributeValue instanceof List ?
			(List<String>) attributeValue :
			Collections.singletonList((String) attributeValue);
	}

	private String getECNumber(SimpleInstance instance) {
		String ecNumber = (String) instance.getAttribute(ReactomeJavaConstants.ecNumber);
		return ecNumber != null ? ecNumber : "";
	}
}
