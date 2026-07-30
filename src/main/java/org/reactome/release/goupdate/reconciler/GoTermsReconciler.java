package org.reactome.release.goupdate.reconciler;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.release.goupdate.GONamespace;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.parser.GoTermParser;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;

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
	private MySQLAdaptor adaptor;
	
	public GoTermsReconciler(MySQLAdaptor adaptor) {
		this.adaptor = adaptor;
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
			@SuppressWarnings("unchecked")
			Collection<GKInstance> goInstances = getInstancesForGoTerm(goTerm);
			if (goInstances == null) {
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
			for (GKInstance goInstance : goInstances) {
				this.adaptor.fastLoadInstanceAttributeValues(goInstance);
				// We'll just grab all relationships in advance.
//				Collection<GKInstance> instancesOfs = new ArrayList<>();
//				Collection<GKInstance> partOfs = new ArrayList<>();
//				Collection<GKInstance> hasParts = new ArrayList<>();
//				if (goInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent)) {
//					instancesOfs = (Collection<GKInstance>) goInstance.getAttributeValuesList(ReactomeJavaConstants.instanceOf);
//					partOfs = (Collection<GKInstance>) goInstance.getAttributeValuesList(ReactomeJavaConstants.componentOf);
//					hasParts = (Collection<GKInstance>) goInstance.getAttributeValuesList("hasPart");
//				}

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

	private void reconcileDefinition(GKInstance goInstance, GoTerm goTerm) throws Exception {
		String goInstanceDefinition = (String) goInstance.getAttributeValue(ReactomeJavaConstants.definition);
		if (!goTerm.getDef().equals(goInstanceDefinition)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'definition';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), goTerm.getDef(), goInstanceDefinition
			);
		}
	}

	private void reconcileName(GKInstance goInstance, GoTerm goTerm) throws Exception {
		String goInstanceName = (String) goInstance.getAttributeValue(ReactomeJavaConstants.name);
		if (!goTerm.getName().equals(goInstanceName)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'name';\n" +
				"\tValue from file: \"{}\";\n" +
				"\tValue from database: \"{}\"",
				goTerm.getId(), goTerm.getName(), goInstanceName
			);
		}
	}

	private void reconcileNamespace(GKInstance goInstance, GoTerm goTerm) {
		String dbNameSpace = goInstance.getSchemClass().getName();
		String fileNameSpace = goTerm.getNamespace().getReactomeName();
		if (!(dbNameSpace.equals(fileNameSpace)
			|| ((dbNameSpace.equals(ReactomeJavaConstants.Compartment) || dbNameSpace.equals(ReactomeJavaConstants.EntityCompartment))
			&& fileNameSpace.equals(GONamespace.cellular_component.getReactomeName())))
		) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'namespace/SchemaClass';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), fileNameSpace, dbNameSpace
			);
		}
	}

	private void reconcileIsA(GKInstance goInstance, GoTerm goTerm) throws Exception {
		if (goInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent)) {
			Collection<GKInstance> instancesOf =
				(Collection<GKInstance>) goInstance.getAttributeValuesList(ReactomeJavaConstants.instanceOf);
			reconcileRelationship(goTerm.getId(), goTerm.getIsA(), instancesOf, IS_A);
		}
	}

	private void reconcilePartOf(GKInstance goInstance, GoTerm goTerm) throws Exception {
		if (goInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent)) {
			Collection<GKInstance> partsOf =
				(Collection<GKInstance>) goInstance.getAttributeValuesList(ReactomeJavaConstants.componentOf);
			reconcileRelationship(goTerm.getId(), goTerm.getPartOf(), partsOf, PART_OF);
		}
	}

	private void reconcileHasPart(GKInstance goInstance, GoTerm goTerm) throws Exception {
		if (goInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent)) {
			Collection<GKInstance> hasParts =
				(Collection<GKInstance>) goInstance.getAttributeValuesList(ReactomeJavaConstants.hasPart);
			reconcileRelationship(goTerm.getId(), goTerm.getHasPart(), hasParts, HAS_PART);
		}
	}

	/**
	 * Reconciles a relationship for a GO term. Will not return, but will log an ERROR message if reconciliation fails.
	 * @param goAccession - The accession of the term to reconcile.
	 * @param goTermRelationAccessions - The relationship accessions from the GO term.
	 * @param relationInstances - A list of GKInstances associated with the corresponding database instance,
	 *                            associated by some relationship.
	 * @throws Exception
	 */
	private void reconcileRelationship(
		String goAccession,
		List<String> goTermRelationAccessions,
		Collection<GKInstance> relationInstances,
		String relationship
	) throws Exception {
		boolean found = false;
		for (String goTermRelationAccession: goTermRelationAccessions) {
			for (GKInstance relationInstance : relationInstances) {
				String accessionFromDB = (String) relationInstance.getAttributeValue(ReactomeJavaConstants.accession);
				if (accessionFromDB.equals(goTermRelationAccession)) {
					found = true;
					// exit the loop early, since a match for accession was found.
					break;
				}
			}
			if (!found) {
				reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"{}\"; " +
						"File says that GO:{} should be present but it is not in the database.",
					goAccession, relationship, goTermRelationAccession
				);
			}
			found = false;
		}
	}

	/**
	 * Reconciles EC Numbers for a GO term, between the data from ec2go file and the database.
	 * Logs an ERROR if EC numbers fail to reconcile.
	 * @param instance - the instance to reconcile.
	 * @param goTerm  - goTerm to compare
	 * @throws Exception
	 */
	private void reconcileECNumbers(GKInstance instance, GoTerm goTerm) throws Exception {
		if (instance.getSchemClass().isValidAttribute(ReactomeJavaConstants.ecNumber)) {
			@SuppressWarnings("unchecked")
			Set<String> ecNumbersFromDB = new HashSet<>(instance.getAttributeValuesList(ReactomeJavaConstants.ecNumber));
			List<String> ecNumbersFromTerm = goTerm.getEcNumbers();
			for (String ecNumberFromTerm : ecNumbersFromTerm) {
				if (!ecNumbersFromDB.contains(ecNumberFromTerm)) {
					reconciliationLogger.error(
						"EC Number {} is in the file for GO Accession {} but is not in the db for that accession.",
						ecNumberFromTerm, instance.getAttributeValue(ReactomeJavaConstants.accession)
					);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private Collection<GKInstance> getInstancesForGoTerm(GoTerm goTerm) throws Exception {
		return this.adaptor.fetchInstanceByAttribute(
			goTerm.getNamespace().getReactomeName(),
			ReactomeJavaConstants.accession,
			"=",
			goTerm.getId()
		);
	}
}
