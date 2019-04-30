package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.InvalidAttributeException;

/**
 * This class should be used to reconcile between the GO file and the database, after updates have been attempted.
 * @author sshorser
 *
 */
public class GoTermsReconciler
{

	private static final Logger reconciliationLogger = LogManager.getLogger("reconciliationLog");
	private MySQLAdaptor adaptor;
	
	public GoTermsReconciler(MySQLAdaptor adaptor)
	{
		this.adaptor = adaptor;
	}
	
	/**
	 * Attempts to reconcile between the database and the terms from the file. Reconciliation reports are logged to a file (not returned).
	 * @param goTermsFromFile - GO terms from the file.
	 * @param goToECNumbers - GO-to-EC Numbers, from the file.
	 * @throws Exception
	 */
	public void reconcile(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<String>> goToECNumbers) throws Exception
	{
		for (String goAccession : goTermsFromFile.keySet())
		{
			Map<String, Object> goTerm = goTermsFromFile.get(goAccession);
			@SuppressWarnings("unchecked")
			Collection<GKInstance> instances = this.adaptor.fetchInstanceByAttribute( ((GONamespace)goTerm.get(GoUpdateConstants.NAMESPACE)).getReactomeName(), ReactomeJavaConstants.accession, "=", goAccession );
			if (instances != null)
			{
				if (instances.size()>1)
				{
					reconciliationLogger.warn("GO Accession {} appears {} times in the database. It should probably only appear once.",goAccession, instances.size());
				}
				for (GKInstance instance : instances)
				{
					this.adaptor.fastLoadInstanceAttributeValues(instance);
					// We'll just grab all relationships in advance.
					Collection<GKInstance> instancesOfs = new ArrayList<>();
					Collection<GKInstance> partOfs = new ArrayList<>();
					Collection<GKInstance> hasParts = new ArrayList<>();
					if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
					{
						instancesOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.instanceOf);
						partOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.componentOf);
						hasParts = (Collection<GKInstance>) instance.getAttributeValuesList("hasPart");
					}
					// goTerm is a map of strings to other objects, read in from the GO file.
					// The keys of the map are the entries for the GO term in the file, such as "id", "name", "namespace", "def", "has_part", etc...
					for (String k : goTerm.keySet())
					{
						switch (k)
						{
							case GoUpdateConstants.DEF:
							{
								String definition = (String) instance.getAttributeValue(ReactomeJavaConstants.definition);
								if (!goTerm.get(k).equals(definition))
								{
									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"definition\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), definition);
								}
								break;
							}
							case GoUpdateConstants.NAME:
							{
								String name = (String) instance.getAttributeValue(ReactomeJavaConstants.name);
								if (!goTerm.get(k).equals(name))
								{
									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"name\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), name);
								}
								break;
							}
							case GoUpdateConstants.NAMESPACE:
							{
								String dbNameSpace = instance.getSchemClass().getName();
								String fileNameSpace = ((GONamespace)goTerm.get(k)).getReactomeName();
								if (!(dbNameSpace.equals(fileNameSpace)
									|| ((dbNameSpace.equals(ReactomeJavaConstants.Compartment) || dbNameSpace.equals(ReactomeJavaConstants.EntityCompartment))
											&& fileNameSpace.equals(GONamespace.cellular_component.getReactomeName())) )
									)
								{
									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"namespace/SchemaClass\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, fileNameSpace, dbNameSpace);
								}
								break;
							}
							case GoUpdateConstants.IS_A:
							{
								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
								{
									GoTermsReconciler.reconcileRelationship(goAccession, goTerm, instancesOfs, k);
								}
								break;
							}
							case GoUpdateConstants.PART_OF:
							{
								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
								{
									GoTermsReconciler.reconcileRelationship(goAccession, goTerm, partOfs, k);
								}
								break;
							}
							case GoUpdateConstants.HAS_PART:
							{
								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
								{
									GoTermsReconciler.reconcileRelationship(goAccession, goTerm, hasParts, k);
								}
								break;
							}
						}
					}
					GoTermsReconciler.reconcileECNumbers(goToECNumbers, instance);
				}
			}
			else
			{
				// If there was not instance returned but the file doesn't mark the file as obsolete, that should be reported.
				if (!((boolean) goTerm.get(GoUpdateConstants.IS_OBSOLETE)))
				{
					reconciliationLogger.warn("GO Accession {} is not present in the database, but is NOT marked as obsolete. GO Term might have been deleted in error, or not properly created.",goAccession);
				}
			}
		}
	}

	/**
	 * Reconciles EC Numbers for a GO term, between the data from ec2go file and the database. Logs an ERROR if EC numbers fail to reconcile.
	 * @param goToECNumbers - the GO-to-EC Number map generated from the ec2go file.
	 * @param instance - the instance to reconcile.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 */
	private static void reconcileECNumbers(Map<String, List<String>> goToECNumbers, GKInstance instance) throws InvalidAttributeException, Exception
	{
		if (instance.getSchemClass().isValidAttribute(ReactomeJavaConstants.ecNumber))
		{
			@SuppressWarnings("unchecked")
			Set<String> ecNumbersFromDB = new HashSet<>(instance.getAttributeValuesList(ReactomeJavaConstants.ecNumber));
			List<String> ecNumbersFromFile = goToECNumbers.get(instance.getAttributeValue(ReactomeJavaConstants.accession));
			if (ecNumbersFromFile!=null)
			{
				for (String ecNumberFromFile : ecNumbersFromFile)
				{
					if (!ecNumbersFromDB.contains(ecNumberFromFile))
					{
						reconciliationLogger.error("EC Nubmer {} is in the file for GO Accession {} but is not in the database for that accession.", ecNumberFromFile, instance.getAttributeValue(ReactomeJavaConstants.accession));
					}
				}
			}
		}
	}

	/**
	 * Reconciles a relationship for a GO term. Will not return, but will log an ERROR message if reconciliation fails.
	 * @param goAccession - The accession of the term to reconcile.
	 * @param goTerm - The GO term, as it was when extracted from the file.
	 * @param relationInstances - A list of GKInstances associated with the corresponding database instance, associated by some relationship.
	 * @param relationship - The relationship to reconcile.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 */
	private static void reconcileRelationship(String goAccession, Map<String, Object> goTerm, Collection<GKInstance> relationInstances, String relationship) throws InvalidAttributeException, Exception {
		boolean found = false;
		@SuppressWarnings("unchecked")
		List<String> relationAccessionsFromFile = (List<String>) goTerm.get(relationship);
		for (String relationAccessionFromFile: relationAccessionsFromFile)
		{
			for (GKInstance relationInstance : relationInstances)
			{
				String accessionFromDB = (String) relationInstance.getAttributeValue(ReactomeJavaConstants.accession);
				if (accessionFromDB.equals(relationAccessionFromFile))
				{
					found = true;
					// exit the loop early, since a match for accession was found.
					break;
				}
			}
			if (!found)
			{
				reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"{}\"; File says that GO:{} should be present but it is not in the database.",goAccession, relationship, relationAccessionFromFile);
			}
			found = false;
		}
	}
}
