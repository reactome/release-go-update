package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.InvalidAttributeException;
import org.reactome.release.common.database.InstanceEditUtils;

/**
 * This class can be used to update GO Terms in the "gk_central" database.
 * @author sshorser
 *
 */
class GoTermsUpdater
{
	private static final Logger logger = LogManager.getLogger();
	private static final Logger reconciliationLogger = LogManager.getLogger("reconciliationLog");
	private static final Logger newMFLogger = LogManager.getLogger("newMolecularFunctionsLog");
	private static final Logger obsoleteAccessionLogger = LogManager.getLogger("obsoleteAccessionLog");
	private static final Logger newGOTermsLogger = LogManager.getLogger("newGOTermsLog");
	private static final Logger updatedGOTermLogger = LogManager.getLogger("updatedGOTermsLog");
	
	private MySQLAdaptor adaptor;
	private List<String> goLines;
	private List<String> ec2GoLines;
	private GKInstance instanceEdit;
	private long personID;
	
	private StringBuffer nameOrDefinitionChangeStringBuilder = new StringBuffer();
	private StringBuffer categoryMismatchStringBuilder = new StringBuffer();
	private StringBuffer deletionStringBuilder = new StringBuffer();
	private StringBuffer updatedRelationshipStringBuilder = new StringBuffer();
	
	private StringBuilder mainOutput = new StringBuilder();
	// this can be static, since there's only one "GO" ReferenceDatabase object in the database.
	private static GKInstance goRefDB;
	
	/**
	 * Creates a new GoTermsUpdater
	 * @param dba - The adaptor to use.
	 * @param goLines - The lines from the GO file, probably it was named "gene_ontology_ext.obo". The <em>must</em> be in the same sequnces as they were in the original file!!
	 * @param ec2GoLines - The lines from the EC-to-GO mapping file, probably named "ec2go".
	 * @param personID - The Person ID that will be used as the author for all created/modified InstanceEdits.
	 */
	public GoTermsUpdater(MySQLAdaptor dba, List<String> goLines, List<String> ec2GoLines, long personID)
	{
		this.adaptor = dba;
		this.goLines = goLines;
		this.ec2GoLines = ec2GoLines;
		this.personID = personID;
		instanceEdit = InstanceEditUtils.createInstanceEdit(this.adaptor, this.personID, this.getClass().getName());
		if (instanceEdit == null)
		{
			logger.fatal("Cannot proceed without a valid InstanceEdit. Aborting.");
			throw new RuntimeException("Cannot proceed without a valid InstanceEdit. Aborting.");
			//System.exit(1);
		}
		try
		{
			GoTermsUpdater.goRefDB = ((Set<GKInstance>) adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=","GO")).stream().findFirst().get();
			logger.info("RefDB for GO: "+GoTermsUpdater.goRefDB.toString());
		}
		catch (Exception e1)
		{
			String message = "Couldn't even get a reference to the GO ReferenceDatabase object. There's no point in continuing, so this progam will exit. :(";
			logger.fatal(message);
			e1.printStackTrace();
			throw new RuntimeException(message);
		}

	}
	
	/**
	 * Executes the GO Terms updates. Returns a StringBuilder, which contains a report about what happened.
	 * @return
	 */
	public StringBuilder updateGoTerms() throws Exception
	{
		// This map is keyed by GO ID. Values are maps of strings that map to values from the file.
		Map<String, Map<String,Object>> goTermsFromFile = new HashMap<String, Map<String,Object>>();
		// This map is keyed by GO Accession number (GO ID).
		Map<String, List<GKInstance>> allGoInstances = getMapOfAllGOInstances(adaptor);
		// This list will track everything that needs to be deleted.
		List<GKInstance> instancesForDeletion = new ArrayList<GKInstance>();
		// A map of things that can't be deleted, and the referrers that prevent it.
		Map<GKInstance,Collection<GKInstance>> undeleteble = new HashMap<GKInstance,Collection<GKInstance>>();
		// Maps GO IDs to EC Numbers.
		Map<String,List<String>> goToECNumbers = new HashMap<String,List<String>>();
		ec2GoLines.stream().filter(line -> !line.startsWith("!")).forEach(line -> processEc2GoLine(line, goToECNumbers));
		
		
		int lineCount = 0;
		int newGoTermCount = 0;
		int obsoleteCount = 0;
		int pendingObsoleteCount = 0;
		int mismatchCount = 0;
		int goTermCount = 0;
		int deletedCount = 0;
		boolean termStarted = false; 
		
		String currentGOID = "";
		for (String line : this.goLines)
		{

			lineCount ++;
			// Empty line means end of a Term.
			if (line.trim().isEmpty())
			{
				termStarted = false;
			}
			// We are starting a new Term.
			else if (line.equals("[Term]"))
			{
				termStarted = true;
				goTermCount++;
			}
			else if (termStarted)
			{
				currentGOID = GoLineProcessor.processLine(line, currentGOID, goTermsFromFile);
			}
		}
		
		// Now process all the goTerms.
		for (String goID : goTermsFromFile.keySet())
		{
			GoTermInstanceModifier goTermModifier;
			GONamespace currentCategory = (GONamespace) goTermsFromFile.get(goID).get(GoUpdateConstants.NAMESPACE);
			//String currentDefinition = (String) goTerms.get(goID).get(GoUpdateConstants.DEF);
			// Now we need to process the Term that was just finished.
			List<GKInstance> goInstances = allGoInstances.get(goID);
			// First let's make sure the GO Term is not obsolete.
			if ( !goTermsFromFile.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && !goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION))
			{
				if (goInstances==null)
				{
					// Create a new Instance if there is nothing in the current list of instances.
					goTermModifier = new GoTermInstanceModifier(this.adaptor, this.instanceEdit);
					Long dbID = goTermModifier.createNewGOTerm(goTermsFromFile, goToECNumbers, goID, currentCategory.getReactomeName(), GoTermsUpdater.goRefDB);
					newGOTermsLogger.info("{}\t{}\t{}",dbID,goID,goTermsFromFile.get(goID));
					if ( ((GONamespace)goTermsFromFile.get(goID).get(GoUpdateConstants.NAMESPACE)).getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction) )
					{
						newMFLogger.info("{}\t{}\t{}", dbID, goID, goTermsFromFile.get(goID).get(GoUpdateConstants.NAME));
					}
					newGoTermCount++;
				}
				else
				{
					// Try to update each goInstance that has the current GO ID.
					for (GKInstance goInst : goInstances)
					{
						// Compartment is a sub-class of GO_CellularComponent - but the GO namespaces don't seem to account for that,
						// we we'll account for that here.
						if (goInst.getSchemClass().getName().equals(currentCategory.getReactomeName()) 
							|| ( (goInst.getSchemClass().getName().equals(ReactomeJavaConstants.Compartment) || goInst.getSchemClass().getName().equals(ReactomeJavaConstants.EntityCompartment) )
									&& currentCategory.getReactomeName().equals(ReactomeJavaConstants.GO_CellularComponent) )
							)
						{
							//Now do the update.
							goTermModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
							goTermModifier.updateGOInstance(goTermsFromFile, goToECNumbers, this.nameOrDefinitionChangeStringBuilder);
						}
						else
						{
							mismatchCount++;
							categoryMismatchStringBuilder.append("Category mismatch! GO ID: ").append(goID).append(" Category in DB: ").append(goInst.getSchemClass().getName()).append(" category in GO file: ").append(currentCategory).append("\n");
							instancesForDeletion.add(goInst);
						}
					}
				}
			}
			else if (goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION) && goTermsFromFile.get(goID).get(GoUpdateConstants.PENDING_OBSOLETION).equals(true))
			{
				// If we have this in our database, it must be reported!
				if (goInstances!=null)
				{
					pendingObsoleteCount++;
					obsoleteAccessionLogger.info("GO:{} ({}) is marked as PENDING obsolete. Consider searching for a replacement.",goID, goInstances.toString());
				}
			}
			else if (goTermsFromFile.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && goTermsFromFile.get(goID).get(GoUpdateConstants.IS_OBSOLETE).equals(true))
			{
				// If we have this in our database, it must be reported!
				if (goInstances!=null)
				{
					obsoleteCount++;
					obsoleteAccessionLogger.warn("GO:{} ({}) marked as OBSOLETE!",goID, goInstances.toString());
					for (GKInstance inst : goInstances)
					{
						instancesForDeletion.add(inst);
					}
				}
				
			}
		}
		
		logger.info("Preparing to delete flagged instances.");
		// Now that the full goTerms structure is complete, and the alternate GO IDs are set up, we can delete the obsolete/category-mismatched GO instances from the database.
		for (GKInstance instance : instancesForDeletion)
		{
			GoTermInstanceModifier goTermModifier = new GoTermInstanceModifier(this.adaptor, instance, this.instanceEdit);
			if (GoTermInstanceModifier.isGoTermDeleteable(instance))
			{
				// Let's get a count of irrelevant referrers 
				Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<GKSchemaAttribute, Integer>();
				for (GKSchemaAttribute attrib : (Set<GKSchemaAttribute>)instance.getSchemClass().getReferers())
				{
					@SuppressWarnings("unchecked")
					Collection<GKInstance> referrers = instance.getReferers(attrib);
					if ( referrers!=null &&  referrers.size() > 0)
					{
						referrersCount.put(attrib, referrers.size());
					}
				}
				obsoleteAccessionLogger.info("Instance \""+instance.toString()+"\" (GO:"+instance.getAttributeValue(ReactomeJavaConstants.accession)+") has referrers but they will not prevent deletion:");
				for (GKSchemaAttribute referrer : referrersCount.keySet())
				{
					obsoleteAccessionLogger.info("\t{} {} referrers.",referrersCount.get(referrer), referrer.getName());
				}
				goTermModifier.deleteGoInstance(goTermsFromFile, allGoInstances, this.deletionStringBuilder);
				deletedCount ++;
			}
			else
			{
				Collection<GKInstance> referrers = GoTermInstanceModifier.getReferrersForGoTerm(instance);
				undeleteble.put(instance,referrers);
				obsoleteAccessionLogger.warn("GO Term {} ({}) cannot be deleted, it has {} referrers: {}", instance.getAttributeValue(ReactomeJavaConstants.accession), instance.toString(), referrers.size(), referrers.toString());
			}
		}
		//Reload the list of GO Instances, since new ones have been created, and old ones have been deleted.
		allGoInstances = getMapOfAllGOInstances(adaptor);
		logger.info("Updating relationships of GO Instances.");
		// Now that the main loop has run, update relationships between GO terms.
		for (String goId : goTermsFromFile.keySet())
		{
			List<GKInstance> goInsts = (List<GKInstance>) allGoInstances.get(goId);
			Map<String, Object> goProps = goTermsFromFile.get(goId);
			if (goInsts != null && !goInsts.isEmpty() && goProps != null && !goProps.isEmpty())
			{
				for (GKInstance goInst : goInsts)
				{
					GoTermInstanceModifier goModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.IS_A, ReactomeJavaConstants.instanceOf, this.updatedRelationshipStringBuilder);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.HAS_PART, "hasPart", this.updatedRelationshipStringBuilder);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.PART_OF, ReactomeJavaConstants.componentOf, this.updatedRelationshipStringBuilder);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.REGULATES, "regulate", this.updatedRelationshipStringBuilder);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.POSITIVELY_REGULATES, "positivelyRegulate", this.updatedRelationshipStringBuilder);
					goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.NEGATIVELY_REGULATES, "negativelyRegulate", this.updatedRelationshipStringBuilder);
					// Update the instanace's "modififed".
					goInst.getAttributeValuesList(ReactomeJavaConstants.modified);
					goInst.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
					adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.modified);
				}
			}
		}
		logger.info("Updating referring instances.");
		// First, get all GO References that have been modified (newly created ones can be ignored because they won't have any non-GO Instance referrers yet).
		List<GKInstance> updatedGOInstances = new ArrayList<GKInstance>();
		updatedGOInstances.addAll(adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.modified, "=", this.instanceEdit.getDBID()));
		updatedGOInstances.addAll(adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.modified, "=", this.instanceEdit.getDBID()));
		updatedGOInstances.addAll(adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.modified, "=", this.instanceEdit.getDBID()));
		
		for (GKInstance inst : updatedGOInstances)
		{
			GoTermInstanceModifier modifier = new GoTermInstanceModifier(this.adaptor, inst, this.instanceEdit);
			modifier.updateReferrersDisplayNames();
		}
		
		
		mainOutput.append("\n*** Category Mismatches: ***\n"+this.categoryMismatchStringBuilder.toString());
		updatedGOTermLogger.info(this.nameOrDefinitionChangeStringBuilder.toString());

		for (GKInstance instance : undeleteble.keySet())
		{
			obsoleteAccessionLogger.info("GO:{} could not be deleted because it had {} referrers: ",instance.getAttributeValue(ReactomeJavaConstants.accession), instance.toString(), undeleteble.get(instance).size());
			for (GKInstance referrer : undeleteble.get(instance))
			{
				GKInstance created = (GKInstance) referrer.getAttributeValue(ReactomeJavaConstants.created);
				GKInstance author = (GKInstance) created.getAttributeValue(ReactomeJavaConstants.author);
				obsoleteAccessionLogger.info("\t\"{}\", created by {} {} @ {}", referrer.toString(), author.getAttributeValue(ReactomeJavaConstants.firstname), author.getAttributeValue(ReactomeJavaConstants.surname), created.getAttributeValue(ReactomeJavaConstants.dateTime));
			}
		}
		
		mainOutput.append(lineCount + " lines from the file were processed.\n");
		mainOutput.append(goTermCount + " GO terms were read from the file.\n");
		mainOutput.append(newGoTermCount + " new GO terms were found (and added to the database).\n");
		mainOutput.append(mismatchCount + " existing GO term instances in the database had mismatched categories when compared to the file (and were deleted from the database).\n");
		mainOutput.append(obsoleteCount + " were obsolete. "+deletedCount+ " were actually deleted and "+undeleteble.size()+" could not be deleted due to existing referrers.\n");
		mainOutput.append(pendingObsoleteCount + " are pending obsolescence (and will probably be deleted at a future date).\n");
		
		reconcile(goTermsFromFile, goToECNumbers);
		
		return mainOutput;
	}
	
	private void reconcile(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<String>> goToECNumbers) throws Exception
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
					// We'll just grab all relationshipsin advance.
					@SuppressWarnings("unchecked")
					Collection<GKInstance> instancesOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.instanceOf);
					@SuppressWarnings("unchecked")
					Collection<GKInstance> partOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.componentOf);
					@SuppressWarnings("unchecked")
					Collection<GKInstance> hasParts = (Collection<GKInstance>) instance.getAttributeValuesList("hasPart");
					Collection<GKInstance> regulates = null;
					Collection<GKInstance> positivelyRegulates = null;
					Collection<GKInstance> negativelyRegulates = null;
					if (!instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
					{
						regulates = (Collection<GKInstance>) instance.getAttributeValuesList("regulate");
						positivelyRegulates = (Collection<GKInstance>) instance.getAttributeValuesList("positivelyRegulate");
						negativelyRegulates = (Collection<GKInstance>) instance.getAttributeValuesList("negativelyRegulate");
					}
					for (String k : goTerm.keySet())
					{
						switch (k)
						{
							case GoUpdateConstants.DEF:
							{
								String definition = (String)instance.getAttributeValue(ReactomeJavaConstants.definition);
								if (!goTerm.get(k).equals(definition))
								{
									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"definition\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), definition);
								}
								break;
							}
							case GoUpdateConstants.NAME:
							{
								String name = (String)instance.getAttributeValue(ReactomeJavaConstants.name);
								if (!goTerm.get(k).equals(name))
								{
									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"name\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), name);
								}
								break;
							}
							case GoUpdateConstants.NAMESPACE:
							{
								String dbNameSpace = (String)instance.getSchemClass().getName();
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
								reconcileRelationship(goAccession, goTerm, instancesOfs, k);
								break;
							}
							case GoUpdateConstants.PART_OF:
							{
								reconcileRelationship(goAccession, goTerm, partOfs, k);
								break;
							}
							case GoUpdateConstants.REGULATES:
							{
								reconcileRelationship(goAccession, goTerm, regulates, k);
								break;
							}
							case GoUpdateConstants.POSITIVELY_REGULATES:
							{
								reconcileRelationship(goAccession, goTerm, positivelyRegulates, k);
								break;
							}
							case GoUpdateConstants.NEGATIVELY_REGULATES:
							{
								reconcileRelationship(goAccession, goTerm, negativelyRegulates, k);
								break;
							}
							case GoUpdateConstants.HAS_PART:
							{
								reconcileRelationship(goAccession, goTerm, hasParts, k);
								break;
							}
						}
					}
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
	 * Reconciles a relationship for a GO term. Will not return, but will log a message if reconciliation fails on something.
	 * @param goAccession - The accession of the term to reconcile.
	 * @param goTerm - The GO term, as it was when extracted from the file.
	 * @param relationInstances - A list of GKInstances associated with the corresponding database instance, associated by some relationship.
	 * @param relationship - The relationship to reconcile.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 */
	private void reconcileRelationship(String goAccession, Map<String, Object> goTerm, Collection<GKInstance> relationInstances, String relationship) throws InvalidAttributeException, Exception {
		boolean found = false;
		@SuppressWarnings("unchecked")
		List<String> relationAccessionsFromFile = (List<String>) goTerm.get(relationship);
		for (String relationAccessionFromFile: relationAccessionsFromFile)
		{
			for (GKInstance i : relationInstances)
			{
				String accessionFromDB = (String)i.getAttributeValue(ReactomeJavaConstants.accession);
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

	/**
	 * Returns a map of all GO-related instances in the databases.
	 * @param adaptor
	 * @return
	 */
	private Map<String, List<GKInstance>> getMapOfAllGOInstances(MySQLAdaptor adaptor)
	{
		Collection<GKInstance> bioProcesses = new ArrayList<GKInstance>();
		Collection<GKInstance> molecularFunctions = new ArrayList<GKInstance>();
		Collection<GKInstance> cellComponents = new ArrayList<GKInstance>();
		try
		{
			bioProcesses = (Collection<GKInstance>) adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
			logger.info(bioProcesses.size() + " GO_BiologicalProcesses in the database.");
			molecularFunctions = (Collection<GKInstance>) adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
			logger.info(molecularFunctions.size() + " GO_MolecularFunction in the database.");
			cellComponents = (Collection<GKInstance>) adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);
			logger.info(cellComponents.size() + " GO_CellularComponent in the database.");
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		
		Map<String, List<GKInstance>> allGoInstances = new HashMap<String, List<GKInstance>>();
		Consumer<? super GKInstance> populateInstMap = inst -> {
			try
			{
				if (!allGoInstances.containsKey((String)(inst.getAttributeValue(ReactomeJavaConstants.accession))))
				{
					allGoInstances.put((String)(inst.getAttributeValue(ReactomeJavaConstants.accession)), new ArrayList<GKInstance>( Arrays.asList(inst) ) );
				}
				else
				{
					allGoInstances.get((String)(inst.getAttributeValue(ReactomeJavaConstants.accession))).add(inst);
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		};
		
		bioProcesses.forEach( populateInstMap);
		cellComponents.forEach( populateInstMap);
		molecularFunctions.forEach( populateInstMap);
		
		return allGoInstances;
	}
	
	/**
	 * Processes a line from the EC-to-GO file.
	 * @param line - The line.
	 * @param goToECNumbers - The map of GO to EC numbers, which will be updated by this function.
	 */
	private void processEc2GoLine(String line, Map<String, List<String>> goToECNumbers)
	{
		Matcher m = GoUpdateConstants.EC_NUMBER_REGEX.matcher(line);
		if (m.matches())
		{
			String ecNumber = m.group(1);
			String goNumber = m.group(2);
			if (goToECNumbers.containsKey(goNumber))
			{
				goToECNumbers.get(goNumber).add(ecNumber);
			}
			else
			{
				List<String> ecNumbers = new ArrayList<String>();
				ecNumbers.add(ecNumber);
				goToECNumbers.put(goNumber, ecNumbers);
			}
		}
	}
	
}
