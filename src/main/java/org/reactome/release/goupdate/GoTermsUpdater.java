package org.reactome.release.goupdate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
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
	private static final Logger obsoleteAccessionLogger = LogManager.getLogger("obsoleteAccessionLog");
	private static final Logger updatedGOTermLogger = LogManager.getLogger("updatedGOTermsLog");
	
	private CSVPrinter newMFPrinter;
	private CSVPrinter obsoleteAccessionPrinter;
	private CSVPrinter newGOTermsPrinter;
	private CSVPrinter replacedGOTermsPrinter;
	private CSVPrinter categoryMismatchPrinter;
	
	private MySQLAdaptor adaptor;
	private List<String> goLines;
	private List<String> ec2GoLines;
	private GKInstance instanceEdit;

	// For each type of InstanceEdit update we have, there is a list of actual GKInstances. The reason is that the instances in each list could vary
	// by having a different Java class name in their note. The class names are to help track down *where* in the code the InstanceEdit was used. 
	private static Map<GOUpdateInstEditType, List<GKInstance>> availableInstanceEdits = new EnumMap<>(GOUpdateInstEditType.class);
	
	private long personID;
	
	private StringBuffer nameOrDefinitionChangeStringBuilder = new StringBuffer();
	private StringBuffer deletionStringBuilder = new StringBuffer();
	
	private StringBuilder mainOutput = new StringBuilder();
	// this can be static, since there's only one "GO" ReferenceDatabase object in the database.
	private static GKInstance goRefDB;
	
	static Predicate<GKInstance> isNotGOEntity = i -> !i.getSchemClass().isa(ReactomeJavaConstants.GO_MolecularFunction)
																&& !i.getSchemClass().isa(ReactomeJavaConstants.GO_BiologicalProcess)
																&& !i.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent);
	enum GOUpdateInstEditType
	{
		NEW("New GO term was created"),
		MODIFIED("GO term attributes were modified"),
		REF_CLEARED("Attribute referring to a GO term has been cleared"),
		REF_ATTRIB_UPDATE("Attribute referring to a GO term has set to a *different* GO term"),
		DISPLAY_NAME("Display Name updated because a GO term was updated"),
		UPDATE_RELATIONSHIP("GO Term relationships were updated");
		
		private String note;
		
		GOUpdateInstEditType(String note)
		{
			this.note = note;
		}
		
		public String getNote()
		{
			return this.note;
		}
	}
	
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
		this.personID = personID;
		
		this.goLines = goLines;
		this.ec2GoLines = ec2GoLines;
		instanceEdit = InstanceEditUtils.createInstanceEdit(this.adaptor, this.personID, this.getClass().getName());
		
		synchronized(availableInstanceEdits)
		{
			if (availableInstanceEdits != null && availableInstanceEdits.isEmpty())
			{
				//Populate the map of available InstanceEdits with actual InstanceEdit objects.
				for (GOUpdateInstEditType instEdType : GOUpdateInstEditType.values())
				{
					availableInstanceEdits.put(instEdType, Arrays.asList(InstanceEditUtils.createDefaultIE(this.adaptor, this.personID, true, instEdType.getNote() + "\n" + this.getClass().getName())));
				}
			}
		}
		
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
		String dateString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
		this.newMFPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get("reports/new_molecular_functions_"+dateString+".csv")), CSVFormat.DEFAULT.withAutoFlush(true).withHeader("DB_ID", "GO ID", "GO Term Name") );
		this.obsoleteAccessionPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get("reports/obsolete_GO_terms_"+dateString+".csv")), CSVFormat.DEFAULT.withAutoFlush(true).withHeader("DB_ID", "GO Type", "Obsolete Term", "Suggested action", "New/replacement GO Terms") );
		this.newGOTermsPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get("reports/new_GO_terms_"+dateString+".csv")), CSVFormat.DEFAULT.withAutoFlush(true).withHeader("DB_ID", "GO Term Name", "GO Term ID", "GO Term Type", "Definition") );
		this.categoryMismatchPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get("reports/category_mismatch_"+dateString+".csv")), CSVFormat.DEFAULT.withAutoFlush(true).withHeader("DB_ID", "GO ID", "Category in Database", "Category in file") );
		this.replacedGOTermsPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get("reports/replaced_GO_terms_"+dateString+".csv")), CSVFormat.DEFAULT.withAutoFlush(true).withHeader("DB_ID", "Primary accession", "Primary Class", "DB_ID (Secondary; to be deleted)", "Secondary accession (to be deleted)", "Secondary Class", "Referrers to be redirected to Primary accession") );
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
			// Now we need to process the Term that was just finished.
			List<GKInstance> goInstances = allGoInstances.get(goID);
			// If there are no objects in allGoInstances whose accession is "goID", we may need to create it.
			if (goInstances==null)
			{
				// BUT... only create a new instance if the data from the file is not Obsolete
				if (!goTermsFromFile.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && !goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION))
				{
					// Create a new Instance if there is nothing in the current list of instances.
					goTermModifier = new GoTermInstanceModifier(this.adaptor, this.instanceEdit);
					newGoTermCount++;
					GKInstance newInst = createNewGOTerm(goTermsFromFile, goToECNumbers, goID, goTermModifier, currentCategory);
					List<GKInstance> instList;
					if (allGoInstances.containsKey(goID))
					{
						instList = allGoInstances.get(goID);
					}
					else
					{
						instList = new ArrayList<>(1);
					}
					instList.add(newInst);
					allGoInstances.put(goID, instList);
				}
			}
			else // update existing instance. Including Obsolete instances, because if they can't be deleted (for some reason) they should be updated so their name/def'n indicates their obsolescence.
			{
				// Try to update each goInstance that has the current GO ID.
				for (GKInstance goInst : goInstances)
				{
					// Compartment is a sub-class of GO_CellularComponent - but the GO namespaces don't seem to account for that,
					// we we'll account for that here.
					boolean isCellularComponentOrSubclass = (goInst.getSchemClass().isa(ReactomeJavaConstants.Compartment)
																|| goInst.getSchemClass().isa(ReactomeJavaConstants.EntityCompartment) )
															&& currentCategory.getReactomeName().equals(ReactomeJavaConstants.GO_CellularComponent);

					// The category is "OK" (i.e. NOT a mismatch) if it matches the Reactome name,
					// OR if it doesn't match exactly, but the current category is CellularComponent
					// and the instance itself is (Entity)Compartment.
					boolean categoryOK = goInst.getSchemClass().getName().equals(currentCategory.getReactomeName()) || isCellularComponentOrSubclass;
					if (categoryOK)
					{
						//Now do the update.
						goTermModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
						goTermModifier.updateGOInstance(goTermsFromFile, goToECNumbers, this.nameOrDefinitionChangeStringBuilder);
					}
					else
					{
						// increment the mismatch counter.
						mismatchCount++;
						this.categoryMismatchPrinter.printRecord(goInst.getDBID(), goID, goInst.getSchemClass().getName(), currentCategory);
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
			
			if (goTermsFromFile.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION) && goTermsFromFile.get(goID).get(GoUpdateConstants.PENDING_OBSOLETION).equals(true))
			{
				// If we have this GO term in our database, it must be reported as "pending obsolete".
				if (goInstances!=null)
				{
					pendingObsoleteCount++;
					String consider = goTermsFromFile.get(goID).get(GoUpdateConstants.CONSIDER) != null ? " Consider: " + goTermsFromFile.get(goID).get(GoUpdateConstants.CONSIDER) : "";
					logger.info("GO:{} ({}) is marked as PENDING obsolete. Consider searching for a replacement.{}",goID, goInstances.toString(), consider);
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
		
		this.categoryMismatchPrinter.close();
		this.newGOTermsPrinter.close();
		this.newMFPrinter.close();
		this.obsoleteAccessionPrinter.close();
		this.replacedGOTermsPrinter.close();

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

						// Update the instance's "modififed".
						goInst.getAttributeValuesList(ReactomeJavaConstants.modified);
						goInst.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
						this.adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.modified);
						// Now, update the displayName of other instances that refers to this GO Term instance.
						goModifier.updateReferrersDisplayNames();
					}
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
			String goAccession = (String) instance.getAttributeValue(ReactomeJavaConstants.accession);
			@SuppressWarnings("unchecked")
			String replacementGOTermAccession = goTermsFromFile.get(goAccession).get(GoUpdateConstants.REPLACED_BY) != null 
												? ((List<String>) goTermsFromFile.get(goAccession).get(GoUpdateConstants.REPLACED_BY)).get(0)
												: "N/A" ;
			
			GoTermInstanceModifier goTermModifier = new GoTermInstanceModifier(this.adaptor, instance, this.instanceEdit);
			if (GoTermInstanceModifier.isGoTermDeleteable(instance))
			{
				// Let's get a count of irrelevant (because there is a replacement instance) referrers
				Map<GKSchemaAttribute, Integer> referrersCount = GoTermsUpdater.getReferrerCountsExcludingGOEntities(instance);
				String action = "";
				if (!referrersCount.isEmpty())
				{
					action = "Automatic Deletion (referrers will be redirected)";
				}
				else
				{
					action = "Automatic Deletion (no referrers)";
				}
				this.obsoleteAccessionPrinter.printRecord(instance.getDBID(), instance.getSchemClass().getName(), instance.getAttributeValue(ReactomeJavaConstants.accession), action, replacementGOTermAccession);
				goTermModifier.deleteGoInstance(goTermsFromFile, allGoInstances, this.deletionStringBuilder);
				deletedCount ++;
			}
			else
			{
				Collection<GKInstance> referrers = GoTermInstanceModifier.getReferrersForGoTerm(instance);
				undeleteble.put(instance,referrers);
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
	 * @param goInstances - A list of GO instances that are identified by goID
	 */
	private void processObsoleteGOTerm(Map<String, Map<String, Object>> goTermsFromFile, List<GKInstance> instancesForDeletion, String goID, List<GKInstance> goInstances)
	{
		StringBuilder attemptToDeleteObsoleteMessage = new StringBuilder();
		Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		// Only add instance(s) to deletion list if they have a valid replacement.
		if (goTermsFromFile.get(goID).get(GoUpdateConstants.REPLACED_BY) != null)
		{
			instancesForDeletion.addAll(goInstances);
			attemptToDeleteObsoleteMessage.append(" Replacement Accession: ").append(goTermsFromFile.get(goID).get(GoUpdateConstants.REPLACED_BY));
		}
		else
		{
			// ...or, if an obsolete term has no replacement AND also has no referrers, it can be 
			// safely be deleted because nothing will be affected.
			// 
			// (Check that the instance has not already been added to instancesForDeletion by some other path)
			goInstances.stream().filter(inst -> !instancesForDeletion.contains(inst)).forEach( inst -> {
				try
				{
					referrersCount.putAll( GoTermsUpdater.getReferrerCountsExcludingGOEntities(inst) );
					if (referrersCount.isEmpty())
					{
						instancesForDeletion.add(inst);
					}
					else // if referrers DO exists, log that, with suggestions about REPLACE_BY/CONSIDER terms.
					{
						@SuppressWarnings("unchecked")
						List<String> replaceByList = (List<String>) goTermsFromFile.get(goID).get(GoUpdateConstants.REPLACED_BY);
						@SuppressWarnings("unchecked")
						List<String> considerList = (List<String>) goTermsFromFile.get(goID).get(GoUpdateConstants.CONSIDER);
						String replaceBy = "";
						String consider = "";
						replaceBy = replaceByList != null && !replaceByList.isEmpty() ? "Replace by: " + String.join(", ", replaceByList) : "";
						consider = considerList != null && !considerList.isEmpty() ? "Consider: " + String.join(", ", considerList) : "";
						String replacementTermString = replaceBy + consider;
						replacementTermString = replacementTermString.length() == 0 ? "N/A" : replacementTermString;
						obsoleteAccessionPrinter.printRecord(inst.getDBID(), inst.getSchemClass().getName(), inst.getAttributeValue(ReactomeJavaConstants.accession), "Manual cleanup (referrers exist)", replacementTermString);
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

		logger.warn("GO:{} ({}) marked as OBSOLETE!{}",goID, goInstances.toString(), attemptToDeleteObsoleteMessage);
	}

	// TODO: These ReferrerCount-related functions could be useful elsewhere. Maybe move to release-common-lib...
	/**
	 * Get referrer counts for an instance.
	 * @param inst - The instance to get counts for.
	 * @return A map whose key is the attrbite that referrs to <code>inst</code>, and the value is the *number* of 
	 * referrers that refer to <code>inst</code> via that attribute.
	 * @throws Exception
	 */
	static Map<GKSchemaAttribute, Integer> getReferrerCounts(GKInstance inst) throws Exception
	{
		return getReferrerCountsFilteredByClass(inst, x -> {return true;} );
	}

	/**
	 * Gets the referrer counts, but excluding Referers that are GO entities
	 * @param inst - The instance to get counts for.
	 * @return A map whose key is the attrbite that referrs to <code>inst</code>, and the value is the *number* of 
	 * referrers that refer to <code>inst</code> via that attribute.
	 * @throws Exception
	 */
	static Map<GKSchemaAttribute, Integer> getReferrerCountsExcludingGOEntities(GKInstance inst) throws Exception
	{
		return getReferrerCountsFilteredByClass(inst, isNotGOEntity);
	}
	
	/**
	 * Gets referrer counts, where the Schema Class of the referrers are filtered by a user-supplied predicate.
	 * @param inst - the Instance to get referrer counts for.
	 * @param classFilter - A Predicate. This predicate will be used to filter the classes of the referrers.
	 * @return A map whose key is the attrbite that referrs to <code>inst</code>, and the value is the *number* of 
	 * referrers that refer to <code>inst</code> via that attribute.
	 * @throws Exception
	 */
	static Map<GKSchemaAttribute, Integer> getReferrerCountsFilteredByClass(GKInstance inst, Predicate<? super GKInstance> classFilter) throws Exception
	{
		Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		for (GKSchemaAttribute attrib : (Collection<GKSchemaAttribute>)inst.getSchemClass().getReferers())
		{
			Collection<GKInstance> referrers = (Collection<GKInstance>) inst.getReferers(attrib);
			
			referrers = referrers.stream().filter(classFilter).collect(Collectors.toList());
			
			if ( referrers!=null && referrers.size() > 0)
			{
				referrersCount.put(attrib, referrers.size());
			}
		}
		return referrersCount;
	}
	
	static List<GKInstance> getReferrersFilteredByClass(GKInstance inst, Predicate<? super GKInstance> classFilter) throws Exception
	{
		List<GKInstance> referrers= new ArrayList<>();
		for (GKSchemaAttribute attrib : (Collection<GKSchemaAttribute>)inst.getSchemClass().getReferers())
		{
			Collection<GKInstance> attribReferrers = (Collection<GKInstance>) inst.getReferers(attrib);
			
			attribReferrers = attribReferrers.stream().filter(classFilter).collect(Collectors.toList());
			
			if ( attribReferrers!=null && attribReferrers.size() > 0)
			{
				referrers.addAll(attribReferrers);
			}
		}
		return referrers;
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
	private GKInstance createNewGOTerm(Map<String, Map<String, Object>> goTermsFromFile, Map<String, List<String>> goToECNumbers, String goID, GoTermInstanceModifier goTermModifier, GONamespace goCategory) throws Exception
	{
		Long dbID = goTermModifier.createNewGOTerm(goTermsFromFile, goToECNumbers, goID, goCategory.getReactomeName(), GoTermsUpdater.goRefDB);
		this.newGOTermsPrinter.printRecord(dbID, goTermsFromFile.get(goID).get(GoUpdateConstants.NAME), goID, goTermsFromFile.get(goID).get(GoUpdateConstants.NAMESPACE), goTermsFromFile.get(goID).get(GoUpdateConstants.DEF));
		if ( ((GONamespace)goTermsFromFile.get(goID).get(GoUpdateConstants.NAMESPACE)).getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction) )
		{
			this.newMFPrinter.printRecord(dbID, goID, goTermsFromFile.get(goID).get(GoUpdateConstants.NAME));
		}
		return this.adaptor.fetchInstance(dbID);
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
						for (GKInstance altGoInst : allGoInstances.get(secondaryAccession))
						{
							logger.info("{} is an alternate/secondary ID for {} - {} will be deleted and its referrers will refer to {}.", secondaryAccession, goID, secondaryAccession, goID);
							try
							{
								this.replacedGOTermsPrinter.printRecord(primaryGOTerm.getDBID(), goID, primaryGOTerm.getSchemClass().getName(),
																		altGoInst.getDBID(), secondaryAccession, altGoInst.getSchemClass().getName(),
																		getReferrersFilteredByClass(altGoInst, isNotGOEntity).stream().map(inst -> inst.toString()).collect(Collectors.joining("; ")) );
							}
							catch (Exception e)
							{
								e.printStackTrace();
							}
							GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, altGoInst, instanceEdit);
							modifier.deleteSecondaryGOInstance(primaryGOTerm, deletionStringBuilder);
						}
					}
				}
			}
		}
	}

	/**
	 * Returns a map of all GO-related instances in the databases.
	 * @param dba
	 * @return
	 */
	private static Map<String, List<GKInstance>> getMapOfAllGOInstances(MySQLAdaptor dba)
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
	private static void processEc2GoLine(String line, Map<String, List<String>> goToECNumbers)
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
