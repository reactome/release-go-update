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
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.release.common.database.InstanceEditUtils;

/**
 * This class can be used to update GO Terms in the "gk_central" database.
 * @author sshorser
 *
 */
class GoTermsUpdater
{
	private static final Logger logger = LogManager.getLogger("GoUpdateLogger");
	private MySQLAdaptor adaptor;
	private List<String> goLines;
	private List<String> ec2GoLines;
	private GKInstance instanceEdit;
	private long personID;
	
	private StringBuffer nameOrDefinitionChangeStringBuilder = new StringBuffer();
	private StringBuffer categoryMismatchStringBuilder = new StringBuffer();
	private StringBuffer obsoletionStringBuilder = new StringBuffer();
	private StringBuffer newGOTermStringBuilder = new StringBuffer();
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
		Map<String, Map<String,Object>> goTerms = new HashMap<String, Map<String,Object>>();
		// This map is keyed by GO Accession number (GO ID).
		Map<String, List<GKInstance>> allGoInstances = getMapOfAllGOInstances(adaptor);
		// This list will track everything that needs to be deleted.
		List<GKInstance> instancesForDeletion = new ArrayList<GKInstance>();
		// Maps GO IDs to EC Numbers.
		Map<String,List<String>> goToECNumbers = new HashMap<String,List<String>>();
		ec2GoLines.stream().filter(line -> !line.startsWith("!")).forEach(line -> processEc2GoLine(line, goToECNumbers));
		
		
		int lineCount = 0;
		int newGoTermCount = 0;
		int obsoleteCount = 0;
		int pendingObsoleteCount = 0;
		int mismatchCount = 0;
		int goTermCount = 0;
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
				currentGOID = processLine(line, currentGOID, goTerms);
			}
		}
		
		// Now process all the goTerms.
		for (String goID : goTerms.keySet())
		{
			GoTermInstanceModifier goTermModifier;
			GONamespace currentCategory = (GONamespace) goTerms.get(goID).get(GoUpdateConstants.NAMESPACE);
			String currentDefinition = (String) goTerms.get(goID).get(GoUpdateConstants.DEF);
			// Now we need to process the Term that was just finished.
			List<GKInstance> goInstances = allGoInstances.get(goID);
			// First let's make sure the GO Term is not obsolete.
			if ( !goTerms.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && !goTerms.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION))
			{
				if (goInstances==null)
				{
					// Create a new Instance if there is nothing in the current list of instances.
					newGOTermStringBuilder.append("New GO Term to create: GO:").append(goID).append(" ").append(goTerms.get(goID)).append("\n");
					goTermModifier = new GoTermInstanceModifier(this.adaptor, this.instanceEdit);
					goTermModifier.createNewGOTerm(goTerms, goToECNumbers, goID, currentCategory.getReactomeName(), GoTermsUpdater.goRefDB);
					
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
							|| ( goInst.getSchemClass().getName().equals(ReactomeJavaConstants.Compartment) && currentCategory.getReactomeName().equals(ReactomeJavaConstants.GO_CellularComponent)))
						{
							//Now do the update.
							goTermModifier = new GoTermInstanceModifier(this.adaptor, goInst, this.instanceEdit);
							goTermModifier.updateGOInstance(goTerms, goToECNumbers, currentDefinition, this.nameOrDefinitionChangeStringBuilder);
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
			else if (goTerms.get(goID).containsKey(GoUpdateConstants.PENDING_OBSOLETION) && goTerms.get(goID).get(GoUpdateConstants.PENDING_OBSOLETION).equals(true))
			{
				// If we have this in our database, it must be reported!
				if (goInstances!=null)
				{
					pendingObsoleteCount++;
					obsoletionStringBuilder.append("GO Instance ").append(goInstances.toString()).append(" are marked as PENDING obsolete!\n");
				}
			}
			else if (goTerms.get(goID).containsKey(GoUpdateConstants.IS_OBSOLETE) && goTerms.get(goID).get(GoUpdateConstants.IS_OBSOLETE).equals(true))
			{
				// If we have this in our database, it must be reported!
				if (goInstances!=null)
				{
					obsoleteCount++;
					obsoletionStringBuilder.append("GO Instance ").append(goInstances.toString()).append(" are marked as OBSOLETE!\n");
					for (GKInstance inst : goInstances)
					{
						instancesForDeletion.add(inst);
					}
				}
				
			}
		}
		
		// Now that the full goTerms structure is complete, and the alternate GO IDs are set up, we can delete the obsolete/category-mismatched GO instances from the database.
		for (GKInstance instance : instancesForDeletion)
		{
			GoTermInstanceModifier goTermModifier = new GoTermInstanceModifier(this.adaptor, instance, this.instanceEdit);
			goTermModifier.deleteGoInstance(goTerms, allGoInstances, this.deletionStringBuilder);
		}
		//Reload the list of GO Instances, since new ones have been created, and old ones have been deleted.
		allGoInstances = getMapOfAllGOInstances(adaptor);
		// Now that the main loop has run, update relationships between GO terms.
		for (String goId : goTerms.keySet())
		{
			List<GKInstance> goInsts = (List<GKInstance>) allGoInstances.get(goId);
			Map<String, Object> goProps = goTerms.get(goId);
			if (goInsts != null && !goInsts.isEmpty())
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
				}
			}
		}
		mainOutput.append("\n*** New GO Terms: ***\n"+this.newGOTermStringBuilder.toString());
		mainOutput.append("\n*** Category Mismatches: ***\n"+this.categoryMismatchStringBuilder.toString());
		mainOutput.append("\n***Update Issues: ***\n"+this.nameOrDefinitionChangeStringBuilder.toString());
		mainOutput.append("\n*** Obsoletion Warnings: ***\n" + this.obsoletionStringBuilder.toString());
		mainOutput.append("\n*** Deletions: ***\n" + this.deletionStringBuilder.toString());
		
		mainOutput.append(lineCount + " lines from the file were processed.\n");
		mainOutput.append(goTermCount + " GO terms were read from the file.\n");
		mainOutput.append(newGoTermCount + " new GO terms were found (and added to the database).\n");
		mainOutput.append(mismatchCount + " existing GO term instances in the database had mismatched categories when compared to the file (and were deleted from the database).\n");
		mainOutput.append(obsoleteCount + " were obsolete (and were deleted).\n");
		mainOutput.append(pendingObsoleteCount + " are pending obsolescence (and will probably be deleted at a future date).\n");
		return mainOutput;
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
			System.out.println(bioProcesses.size() + " GO_BiologicalProcesses in the database.");
			molecularFunctions = (Collection<GKInstance>) adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
			System.out.println(molecularFunctions.size() + " GO_MolecularFunction in the database.");
			cellComponents = (Collection<GKInstance>) adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);
			System.out.println(cellComponents.size() + " GO_CellularComponent in the database.");
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
	 * Process a line from the GO file.
	 * @param line - The line.
	 * @param goTerms - The GO terms map. This map will be updated by this function.
	 * @param currentGOID - The ID of the GO term currently being processed, line by line.
	 */
	private String processLine(String line, String currentGOID, Map<String, Map<String,Object>> goTerms)
	{
		String goID = currentGOID;
		Matcher m;
		try
		{
			m = GoUpdateConstants.LINE_DECODER.matcher(line);
			if (m.matches())
			{
				String lineCode = m.group(1);
				switch (lineCode)
				{
					case GoUpdateConstants.ID:
					{
						m = GoUpdateConstants.GO_ID_REGEX.matcher(line);
						goID = m.matches() ? m.group(1) : "";
						// Were we able to extract a GO ID?
						if (!goID.trim().isEmpty())
						{
							if (!goTerms.containsKey(goID))
							{
								goTerms.put(goID, new HashMap<String,Object>());
								currentGOID = goID;
							}
							else
							{
								logger.warn("GO ID {} has appeared more than once in the input!", goID);
								// TODO: exit is probably not the best way to handle this. only for early-development debugging...
								//System.exit(1);
							}
						}
						break;
					}
					case GoUpdateConstants.ALT_ID:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.ALT_ID_REGEX, GoUpdateConstants.ALT_ID);
						break;
					}
					case GoUpdateConstants.NAME:
					{
						m = GoUpdateConstants.NAME_REGEX.matcher(line);
						String name = m.matches() ? m.group(1) : "";
						if (!name.trim().isEmpty())
						{
							if (!goTerms.get(currentGOID).containsKey(name))
							{
								goTerms.get(currentGOID).put(GoUpdateConstants.NAME, name);
							}
							else
							{
								logger.fatal("GO ID {} *already* has a value for NAME ({}) - and this is a single-value field!", currentGOID, goTerms.get(currentGOID).get(name));
								// TODO: exit is probably not the best way to handle this. only for early-development debugging...
								System.exit(1);
							}
						}
						break;
					}
					case GoUpdateConstants.NAMESPACE:
					{
						m = GoUpdateConstants.NAMESPACE_REGEX.matcher(line);
						String namespace = m.matches() ? m.group(1) : "";
						if (!namespace.trim().isEmpty())
						{
							goTerms.get(currentGOID).put(GoUpdateConstants.NAMESPACE, GONamespace.valueOf(namespace));
						}
						break;
					}
					case GoUpdateConstants.DEF:
					{
						m = GoUpdateConstants.DEF_REGEX.matcher(line);
						String def = m.matches() ? m.group(1) : "";
						if (!def.trim().isEmpty())
						{
							goTerms.get(currentGOID).put(GoUpdateConstants.DEF, def);
						}
						break;
					}
					case GoUpdateConstants.IS_A:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.IS_A_REGEX, GoUpdateConstants.IS_A);
						break;
					}
					case GoUpdateConstants.SYNONYM:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.SYNONYM_REGEX, GoUpdateConstants.SYNONYM);
						break;
					}
					case GoUpdateConstants.CONSIDER:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.CONSIDER_REGEX, GoUpdateConstants.CONSIDER);
						break;
					}
					case GoUpdateConstants.REPLACED_BY:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.REPLACED_BY_REGEX, GoUpdateConstants.REPLACED_BY);
						break;
					}
					case GoUpdateConstants.IS_OBSOLETE:
					{
						m = GoUpdateConstants.IS_OBSOLETE_REGEX.matcher(line);
						if (m.matches())
						{
							goTerms.get(currentGOID).put(GoUpdateConstants.IS_OBSOLETE, true);
						}
						break;
					}
					case GoUpdateConstants.RELATIONSHIP:
					{
						m = GoUpdateConstants.RELATIONSHIP_DECODER.matcher(line);
						if (m.matches())
						{
							String relationShipType = m.group(1);
							switch (relationShipType)
							{
								case GoUpdateConstants.HAS_PART:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.RELATIONSHIP_HAS_PART_REGEX, GoUpdateConstants.HAS_PART);
									break;
								}
								case GoUpdateConstants.PART_OF:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.RELATIONSHIP_PART_OF_REGEX, GoUpdateConstants.PART_OF);
									break;
								}
								case GoUpdateConstants.REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.RELATIONSHIP_REGULATES_REGEX, GoUpdateConstants.REGULATES);
									break;
								}
								case GoUpdateConstants.POSITIVELY_REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.RELATIONSHIP_POSITIVELY_REGULATES_REGEX, GoUpdateConstants.POSITIVELY_REGULATES);
									break;
								}
								case GoUpdateConstants.NEGATIVELY_REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.RELATIONSHIP_NEGATIVELY_REGULATES_REGEX, GoUpdateConstants.NEGATIVELY_REGULATES);
									break;
								}
							}
						}
						break;
					}
					default:
					{
						// handle other cases here...
						// ...such as...??
						//
						// check for pending obsoletion
						m = GoUpdateConstants.OBSOLETION.matcher(line);
						if (m.matches())
						{
							goTerms.get(currentGOID).put(GoUpdateConstants.PENDING_OBSOLETION, true);
						}
						break;
					}
					
				}
			}
		}
		catch (Exception e)
		{
			if (!e.getMessage().equals("No match found"))
			{
				// no match found is OK, but anything else should be raised.
				throw e;
			}
		}
		return goID;
	}

	/**
	 * Adds a field to a multi-valued attribute on a GO term.
	 * @param goTerms - The map of all GO terms from the file.
	 * @param currentGOID - The current GO ID of the GO term being processed.
	 * @param line - The line.
	 * @param pattern - The regex pattern to use to extract the value from the line.
	 * @param key - The attribute (as the key) to use to insert the value under this GO term in the main map of GO terms.
	 */
	@SuppressWarnings("unchecked")
	private void addToMultivaluedAttribute(Map<String, Map<String, Object>> goTerms, String currentGOID, String line, Pattern pattern, String key)
	{
		Matcher m;
		m = pattern.matcher(line);
		String extractedValue = m.matches() ? m.group(1) : "";
		if (!extractedValue.trim().isEmpty())
		{
			List<String> listOfValues = (List<String>) goTerms.get(currentGOID).get(key);
			if (listOfValues == null)
			{
				listOfValues = new ArrayList<String>();
				listOfValues.add(extractedValue);
				goTerms.get(currentGOID).put(key, listOfValues);
			}
			else
			{
				((List<String>) goTerms.get(currentGOID).get(key)).add(extractedValue);
			}
		}
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
