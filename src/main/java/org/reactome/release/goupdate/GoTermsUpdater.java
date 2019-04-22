package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import org.gk.schema.InvalidAttributeValueException;
import org.reactome.release.common.database.InstanceEditUtils;

/**
 * This class can be used to update GOTerms in the "gk_central" database.
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
	
	private StringBuilder mainOutput = new StringBuilder();
	// this can be static, since there's only one "GO" ReferenceDatabase object in the database.
	private static GKInstance goRefDB;
	
	/**
	 * Creates a new GoTermsUpdater
	 * @param dba - The adaptor to use.
	 * @param goLines - The lines from the GO file, probably it was named "gene_ontology_ext.obo". The <em>must</em> be in the same sequnces as they were in the original file!!
	 * @param ec2GoLines - The lines from the EC-to-GO mapping file, probably named "ec2go".
	 * @param personID - The Person ID that will be used as the author for all created/modified InstanceEdits.
	 * @throws Exception 
	 */
	public GoTermsUpdater(MySQLAdaptor dba, List<String> goLines, List<String> ec2GoLines, long personID) throws Exception
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
		}
		try
		{
			// Grab a copy of the GKInstance representing the GO Database
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
		Map<String, Map<String,Object>> goTermsFromFile = new HashMap<>();
		// This map is keyed by GO Accession number (GO ID).
		Map<String, List<GKInstance>> allGoInstances = getMapOfAllGOInstances(adaptor);
		// This list will track everything that needs to be deleted.
		List<GKInstance> instancesForDeletion = new ArrayList<>();
		// A map of things that can't be deleted, and the referrers that prevent it.
		Map<GKInstance,Collection<GKInstance>> undeleteble = new HashMap<>();
		// Maps GO IDs to EC Numbers.
		Map<String,List<String>> goToECNumbers = new HashMap<>();
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
			// First let's make sure the GO Term is not (pending) obsolete.
			if ( !goTermsFromFile.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && !goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION))
			{
				if (goInstances==null)
				{
					// Create a new Instance if there is nothing in the current list of instances.
					goTermModifier = new GoTermInstanceModifier(this.adaptor, this.instanceEdit);
					newGoTermCount++;
					createNewGOTerm(goTermsFromFile, goToECNumbers, goID, goTermModifier, currentCategory);
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
							// Delete the instance. Don't use the GO Term modifier since it will check for a "replaced_by" value.
							// In this case, the GO Term is not obsolete but it has the wrong category, so it should be removed and recreated.
							this.adaptor.deleteByDBID(goInst.getDBID());
							// Now re-create the GO term with the correct GO type.
							goTermModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
							newGoTermCount++;
							createNewGOTerm(goTermsFromFile, goToECNumbers, goID, goTermModifier, currentCategory);
						}
					}
				}
				processAlternates(goTermsFromFile, allGoInstances, goID);
			}
			else if (goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION) && goTermsFromFile.get(goID).get(GoUpdateConstants.PENDING_OBSOLETION).equals(true))
			{
				// If we have this GO term in our database, it must be reported as "pending obsolete".
				if (goInstances!=null)
				{
					pendingObsoleteCount++;
					String consider = goTermsFromFile.get(goID).get(GoUpdateConstants.CONSIDER) != null ? " Consider: " + goTermsFromFile.get(goID).get(GoUpdateConstants.CONSIDER) : "";
					obsoleteAccessionLogger.info("GO:{} ({}) is marked as PENDING obsolete. Consider searching for a replacement.{}",goID, goInstances.toString(), consider);
				}
			}
			else if (goTermsFromFile.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && goTermsFromFile.get(goID).get(GoUpdateConstants.IS_OBSOLETE).equals(true))
			{
				// If we have this in our database, it must be reported as obsolete!
				if (goInstances!=null)
				{
					obsoleteCount++;
					processObsoleteGOTerm(goTermsFromFile, instancesForDeletion, goID, goInstances);
				}
			}
		}
		
		logger.info("Preparing to delete flagged instances.");
		// Now that the full goTerms structure is complete, and the alternate GO IDs are set up, we can delete the obsolete/category-mismatched GO instances from the database.
		deletedCount = deleteFlaggedInstances(goTermsFromFile, allGoInstances, instancesForDeletion, undeleteble);
		//Reload the list of GO Instances, since new ones have been created, and old ones have been deleted.
		allGoInstances = getMapOfAllGOInstances(adaptor);
		logger.info("Updating relationships of GO Instances.");
		// Now that the main loop has run, update relationships between GO terms.
		updateRelationships(goTermsFromFile, allGoInstances);
		
		mainOutput.append("\n*** Category Mismatches: ***\n"+this.categoryMismatchStringBuilder.toString());
		updatedGOTermLogger.info(this.nameOrDefinitionChangeStringBuilder.toString());

		for (GKInstance instance : undeleteble.keySet())
		{
			obsoleteAccessionLogger.info("GO:{} ({}) could not be deleted because it had {} referrers: ",instance.getAttributeValue(ReactomeJavaConstants.accession), instance.toString(), undeleteble.get(instance).size());
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
		mainOutput.append(obsoleteCount + " were obsolete. "+deletedCount+ " were actually deleted, and "+undeleteble.size()+" could not be deleted due to existing referrers.\n");
		mainOutput.append(pendingObsoleteCount + " are pending obsolescence (and will probably be deleted at a future date).\n");
		GoTermsReconciler reconciler = new GoTermsReconciler(this.adaptor);
		reconciler.reconcile(goTermsFromFile, goToECNumbers);
		
		return mainOutput;
	}

	/**
	 * Updates the relationships of GO terms.
	 * @param goTermsFromFile - the GO terms from the GO file.
	 * @param allGoInstances - a map of ALL GO instances from the database.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 * @throws InvalidAttributeValueException
	 */
	private void updateRelationships(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<GKInstance>> allGoInstances) throws InvalidAttributeException, Exception, InvalidAttributeValueException
	{
		for (String goId : goTermsFromFile.keySet())
		{
			List<GKInstance> goInsts = allGoInstances.get(goId);
			Map<String, Object> goProps = goTermsFromFile.get(goId);
			if (goInsts != null && !goInsts.isEmpty() && goProps != null && !goProps.isEmpty())
			{
				for (GKInstance goInst : goInsts)
				{
					GoTermInstanceModifier goModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
					
					if (goInst.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
					{
						goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.IS_A, ReactomeJavaConstants.instanceOf);
						goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.HAS_PART, "hasPart");
						goModifier.updateRelationship(allGoInstances, goProps, GoUpdateConstants.PART_OF, ReactomeJavaConstants.componentOf);
					}
					// Update the instanace's "modififed".
					goInst.getAttributeValuesList(ReactomeJavaConstants.modified);
					goInst.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
					this.adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.modified);
					// Now, update the displayName of other instances that refers to this GO Term instance.
					goModifier.updateReferrersDisplayNames();
				}
			}
		}
	}

	/**
	 * Deletes GO instances that have been flagged for deletion.
	 * @param goTermsFromFile - A map of GO terms from the file.
	 * @param allGoInstances - A map of ALL GO terms from the database.
	 * @param instancesForDeletion - A list of instances that must be deleted.
	 * @param undeleteble - A map of instances that are undeleteable (probably because they have no replacement instance AND they are referred to by other instances). This map will be modified by the method.
	 * @return The number of instances that were actually deleted. 
	 * @throws Exception
	 * @throws InvalidAttributeException
	 */
	private int deleteFlaggedInstances(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<GKInstance>> allGoInstances, List<GKInstance> instancesForDeletion, Map<GKInstance, Collection<GKInstance>> undeleteble) throws Exception, InvalidAttributeException
	{
		int deletedCount = 0;
		for (GKInstance instance : instancesForDeletion)
		{
			GoTermInstanceModifier goTermModifier = new GoTermInstanceModifier(this.adaptor, instance, this.instanceEdit);
			if (GoTermInstanceModifier.isGoTermDeleteable(instance))
			{
				// Let's get a count of irrelevant (because there is a replacement instance) referrers
				Map<GKSchemaAttribute, Integer> referrersCount = GoTermsUpdater.getReferrerCounts(instance);
				if (!referrersCount.isEmpty())
				{
					obsoleteAccessionLogger.info("Instance \"{}\" (GO:{}) has {} referrers but they will not prevent deletion.", instance.toString(), instance.getAttributeValue(ReactomeJavaConstants.accession), referrersCount.keySet().size());
					for (GKSchemaAttribute referrer : referrersCount.keySet())
					{
						obsoleteAccessionLogger.info("\t{} {} referrers.",referrersCount.get(referrer), referrer.getName());
					}
				}
				else
				{
					obsoleteAccessionLogger.info("Instance \"{}\" (GO:{}) has no referrers and will be deleted.", instance.toString(), instance.getAttributeValue(ReactomeJavaConstants.accession));
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
		return deletedCount;
	}

	/**
	 * Processes a single GO Term that is obsolete. This involves examining them and flagging them for deletion if possible. If it is not possible to delete the instance
	 * (usually because there ARE referrers and there is NO suggested replacement) a message will be logged suggesting manual cleanup.
	 * @param goTermsFromFile - The GO terms from the file. 
	 * @param instancesForDeletion - A list of instances for deletion. This list will be modified by this function!
	 * @param goID - The GO ID of the term to process.
	 * @param goInstances - A list of GO instances.
	 */
	private void processObsoleteGOTerm(Map<String, Map<String, Object>> goTermsFromFile, List<GKInstance> instancesForDeletion, String goID, List<GKInstance> goInstances)
	{
		StringBuilder attemptToDeleteObsoleteMessage = new StringBuilder();
		Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		// Only add instance(s) to deletion list if they have a valid replacement.
		if (goTermsFromFile.get(goID).get(GoUpdateConstants.REPLACED_BY) != null)
		{
			instancesForDeletion.addAll(goInstances);
			attemptToDeleteObsoleteMessage.append(" Replacement Accesion: ").append(goTermsFromFile.get(goID).get(GoUpdateConstants.REPLACED_BY));
		}
		else
		{
			// ...or, if an obsolete term has no replacement AND also has no referreres, it can be 
			// safely be deleted because nothing will be affected.
			// 
			// (Check that the instance has not already been added to instancesForDeletion by some other path)
			goInstances.stream().filter(inst -> !instancesForDeletion.contains(inst)).forEach( inst -> {
				try
				{
					referrersCount.putAll( GoTermsUpdater.getReferrerCounts(inst) );
					if (referrersCount.isEmpty())
					{
						instancesForDeletion.add(inst);
						attemptToDeleteObsoleteMessage.append(" No replacement accession, but that's OK because this instance (").append(inst.toString()).append(") has no referrers, and it will be safely deleted.");
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
					obsoleteAccessionLogger.error(e);
					attemptToDeleteObsoleteMessage.append(" An exception occcured while trying to get the number of referrers - this instance will not be deleted. Manual clean up may be necessary.");
				}
			});
		}
		// If attemptToDeleteObsoleteMessage is empty, it means that no replacement value is available AND there ARE referrers,
		// so the message must be set to reflect this.
		if (attemptToDeleteObsoleteMessage.length() == 0)
		{
			attemptToDeleteObsoleteMessage.append(" ** Manual cleanup for this term may be necessary! ** No replacement was suggested by GO, and referrering instances seem to exist (").append(referrersCount.toString()).append("), so GO term will NOT be deleted.");
		}
		obsoleteAccessionLogger.warn("GO:{} ({}) marked as OBSOLETE!{}",goID, goInstances.toString(), attemptToDeleteObsoleteMessage);
	}

	// TODO: This could be useful elsewhere. Maybe move to release-common-lib...
	/**
	 * Get referrer counts for an instance.
	 * @param inst - The instance to get counts for.
	 * @return A map whose key is the attrbite that referrs to <code>inst</code>, and the value is the *number* of 
	 * referrers that refer to <code>inst</code> via that attribute.
	 * @throws Exception
	 */
	protected static Map<GKSchemaAttribute, Integer> getReferrerCounts(GKInstance inst) throws Exception
	{
		Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		for (GKSchemaAttribute attrib : (Collection<GKSchemaAttribute>)inst.getSchemClass().getReferers())
		{
			@SuppressWarnings("unchecked")
			Collection<GKInstance> referrers = inst.getReferers(attrib);
			if ( referrers!=null &&  referrers.size() > 0)
			{
				referrersCount.put(attrib, referrers.size());
			}
		}
		return referrersCount;
	}

	/**
	 * Creates a new GO term.
	 * @param goTermsFromFile - The go terms from the file.
	 * @param goToECNumbers - The Mapping of GO IDs to EC Numbers.
	 * @param newGoTermCount - The nubmer of new GO terms so far.
	 * @param goID - The GO ID of the new GO term.
	 * @param goTermModifier - A GO Term Modifier, which will do the actual creation.
	 * @param goCategory - The *type* pf GO term this will be.
	 * @return
	 * @throws Exception
	 */
	private void createNewGOTerm(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<String>> goToECNumbers, String goID, GoTermInstanceModifier goTermModifier, GONamespace goCategory) throws Exception
	{
		Long dbID = goTermModifier.createNewGOTerm(goTermsFromFile, goToECNumbers, goID, goCategory.getReactomeName(), GoTermsUpdater.goRefDB);
		newGOTermsLogger.info("{}\t{}\t{}",dbID,goID,goTermsFromFile.get(goID));
		if ( ((GONamespace)goTermsFromFile.get(goID).get(GoUpdateConstants.NAMESPACE)).getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction) )
		{
			newMFLogger.info("{}\t{}\t{}", dbID, goID, goTermsFromFile.get(goID).get(GoUpdateConstants.NAME));
		}
	}

	/**
	 * Process alternate GO terms for a given GO ID. This involves deleting secondary identifiers and then redirecting the referrers for those
	 * to the instance whose GO ID is <code>goID</code>
	 * @param goTermsFromFile - GO terms from the GO file.
	 * @param allGoInstances - A map of ALL GO Terms in the database.
	 * @param goID - the GO ID of the term to process alternates for.
	 */
	private void processAlternates(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<GKInstance>> allGoInstances, String goID)
	{
		if (goTermsFromFile.get(goID).get(GoUpdateConstants.ALT_ID) != null && allGoInstances.containsKey(goID))
		{
			@SuppressWarnings("unchecked")
			List<String> alternates = (List<String>) goTermsFromFile.get(goID).get(GoUpdateConstants.ALT_ID);
			for (GKInstance primaryGOTerm : allGoInstances.get(goID))
			{
				// Now that we have a list of alternates for *this* accession, we need to mark them for deletion and have their referrers refer to *this* accession.
				for (String secondaryAccession : alternates)
				{
					// Check that we're even using this secondary accession.
					if (allGoInstances.get(secondaryAccession) != null)
					{
						logger.info("{} is an alternate/secondary ID for {} - {} will be deleted and its referrers will refer to {}.", secondaryAccession, goID, secondaryAccession, goID);
						for (GKInstance altGoInst : allGoInstances.get(secondaryAccession))
						{
							GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, altGoInst, instanceEdit);
							modifier.deleteSecondaryGOInstance(primaryGOTerm, deletionStringBuilder);
						}
					}
				}
			}
		}
	}
	
//	private void reconcile(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<String>> goToECNumbers) throws Exception
//	{
//		for (String goAccession : goTermsFromFile.keySet())
//		{
//			Map<String, Object> goTerm = goTermsFromFile.get(goAccession);
//			@SuppressWarnings("unchecked")
//			Collection<GKInstance> instances = this.adaptor.fetchInstanceByAttribute( ((GONamespace)goTerm.get(GoUpdateConstants.NAMESPACE)).getReactomeName(), ReactomeJavaConstants.accession, "=", goAccession );
//			if (instances != null)
//			{
//				if (instances.size()>1)
//				{
//					reconciliationLogger.warn("GO Accession {} appears {} times in the database. It should probably only appear once.",goAccession, instances.size());
//				}
//				for (GKInstance instance : instances)
//				{
//					this.adaptor.fastLoadInstanceAttributeValues(instance);
//					// We'll just grab all relationships in advance.
//					Collection<GKInstance> instancesOfs = new ArrayList<>();
//					Collection<GKInstance> partOfs = new ArrayList<>();
//					Collection<GKInstance> hasParts = new ArrayList<>();
//					if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
//					{
//						instancesOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.instanceOf);
//						partOfs = (Collection<GKInstance>) instance.getAttributeValuesList(ReactomeJavaConstants.componentOf);
//						hasParts = (Collection<GKInstance>) instance.getAttributeValuesList("hasPart");
//					}
//
//					for (String k : goTerm.keySet())
//					{
//						switch (k)
//						{
//							case GoUpdateConstants.DEF:
//							{
//								String definition = (String) instance.getAttributeValue(ReactomeJavaConstants.definition);
//								if (!goTerm.get(k).equals(definition))
//								{
//									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"definition\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), definition);
//								}
//								break;
//							}
//							case GoUpdateConstants.NAME:
//							{
//								String name = (String) instance.getAttributeValue(ReactomeJavaConstants.name);
//								if (!goTerm.get(k).equals(name))
//								{
//									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"name\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, goTerm.get(k), name);
//								}
//								break;
//							}
//							case GoUpdateConstants.NAMESPACE:
//							{
//								String dbNameSpace = instance.getSchemClass().getName();
//								String fileNameSpace = ((GONamespace)goTerm.get(k)).getReactomeName();
//								if (!(dbNameSpace.equals(fileNameSpace)
//									|| ((dbNameSpace.equals(ReactomeJavaConstants.Compartment) || dbNameSpace.equals(ReactomeJavaConstants.EntityCompartment))
//											&& fileNameSpace.equals(GONamespace.cellular_component.getReactomeName())) )
//									)
//								{
//									reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"namespace/SchemaClass\";\n\tValue from file: \"{}\";\n\tValue from database: \"{}\"",goAccession, fileNameSpace, dbNameSpace);
//								}
//								break;
//							}
//							case GoUpdateConstants.IS_A:
//							{
//								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
//								{
//									reconcileRelationship(goAccession, goTerm, instancesOfs, k);
//								}
//								break;
//							}
//							case GoUpdateConstants.PART_OF:
//							{
//								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
//								{
//									reconcileRelationship(goAccession, goTerm, partOfs, k);
//								}
//								break;
//							}
//							case GoUpdateConstants.HAS_PART:
//							{
//								if (instance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
//								{
//									reconcileRelationship(goAccession, goTerm, hasParts, k);
//								}
//								break;
//							}
//						}
//					}
//					reconcileECNumbers(goToECNumbers, instance);
//				}
//			}
//			else
//			{
//				// If there was not instance returned but the file doesn't mark the file as obsolete, that should be reported.
//				if (!((boolean) goTerm.get(GoUpdateConstants.IS_OBSOLETE)))
//				{
//					reconciliationLogger.warn("GO Accession {} is not present in the database, but is NOT marked as obsolete. GO Term might have been deleted in error, or not properly created.",goAccession);
//				}
//			}
//		}
//	}
//
//	/**
//	 * Reconciles EC Numbers for a GO term, between the data from ec2go file and the database. Logs an ERROR if EC numbers fail to reconcile.
//	 * @param goToECNumbers - the GO-to-EC Number map generated from the ec2go file.
//	 * @param instance - the instance to reconcile.
//	 * @throws InvalidAttributeException
//	 * @throws Exception
//	 */
//	private void reconcileECNumbers(Map<String, List<String>> goToECNumbers, GKInstance instance) throws InvalidAttributeException, Exception
//	{
//		if (instance.getSchemClass().isValidAttribute(ReactomeJavaConstants.ecNumber))
//		{
//			@SuppressWarnings("unchecked")
//			Set<String> ecNumbersFromDB = new HashSet<>(instance.getAttributeValuesList(ReactomeJavaConstants.ecNumber));
//			List<String> ecNumbersFromFile = goToECNumbers.get(instance.getAttributeValue(ReactomeJavaConstants.accession));
//			if (ecNumbersFromFile!=null)
//			{
//				for (String ecNumberFromFile : ecNumbersFromFile)
//				{
//					if (!ecNumbersFromDB.contains(ecNumberFromFile))
//					{
//						logger.error("EC Nubmer {} is in the file for GO Accession {} but is not in the database for that accession.", ecNumberFromFile, instance.getAttributeValue(ReactomeJavaConstants.accession));
//					}
//				}
//			}
//		}
//	}
//
//	/**
//	 * Reconciles a relationship for a GO term. Will not return, but will log an ERROR message if reconciliation fails.
//	 * @param goAccession - The accession of the term to reconcile.
//	 * @param goTerm - The GO term, as it was when extracted from the file.
//	 * @param relationInstances - A list of GKInstances associated with the corresponding database instance, associated by some relationship.
//	 * @param relationship - The relationship to reconcile.
//	 * @throws InvalidAttributeException
//	 * @throws Exception
//	 */
//	private void reconcileRelationship(String goAccession, Map<String, Object> goTerm, Collection<GKInstance> relationInstances, String relationship) throws InvalidAttributeException, Exception {
//		boolean found = false;
//		@SuppressWarnings("unchecked")
//		List<String> relationAccessionsFromFile = (List<String>) goTerm.get(relationship);
//		for (String relationAccessionFromFile: relationAccessionsFromFile)
//		{
//			for (GKInstance i : relationInstances)
//			{
//				String accessionFromDB = (String)i.getAttributeValue(ReactomeJavaConstants.accession);
//				if (accessionFromDB.equals(relationAccessionFromFile))
//				{
//					found = true;
//					// exit the loop early, since a match for accession was found.
//					break;
//				}
//			}
//			if (!found)
//			{
//				reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"{}\"; File says that GO:{} should be present but it is not in the database.",goAccession, relationship, relationAccessionFromFile);
//			}
//			found = false;
//		}
//	}

	/**
	 * Returns a map of all GO-related instances in the databases.
	 * @param dba
	 * @return
	 */
	private Map<String, List<GKInstance>> getMapOfAllGOInstances(MySQLAdaptor dba)
	{
		Collection<GKInstance> bioProcesses = new ArrayList<>();
		Collection<GKInstance> molecularFunctions = new ArrayList<>();
		Collection<GKInstance> cellComponents = new ArrayList<>();
		try
		{
			bioProcesses = (Collection<GKInstance>) dba.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
			logger.info(bioProcesses.size() + " GO_BiologicalProcesses in the database.");
			molecularFunctions = (Collection<GKInstance>) dba.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
			logger.info(molecularFunctions.size() + " GO_MolecularFunction in the database.");
			cellComponents = (Collection<GKInstance>) dba.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);
			logger.info(cellComponents.size() + " GO_CellularComponent in the database.");
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		
		Map<String, List<GKInstance>> allGoInstances = new HashMap<>();
		Consumer<? super GKInstance> populateInstMap = inst -> {
			try
			{
				if (!allGoInstances.containsKey((String)(inst.getAttributeValue(ReactomeJavaConstants.accession))))
				{
					allGoInstances.put((String)(inst.getAttributeValue(ReactomeJavaConstants.accession)), new ArrayList<>( Arrays.asList(inst) ) );
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
				List<String> ecNumbers = new ArrayList<>();
				ecNumbers.add(ecNumber);
				goToECNumbers.put(goNumber, ecNumbers);
			}
		}
	}
}
