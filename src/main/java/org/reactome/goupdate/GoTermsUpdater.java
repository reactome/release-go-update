package org.reactome.goupdate;

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

import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.gk.schema.SchemaClass;

class GoTermsUpdater
{

	private MySQLAdaptor adaptor;
	private List<String> goLines;
	private List<String> ec2GoLines;
	private String currentGOID = "";
	private String currentCategory = "";
	private String currentDefinition = "";
	
	private StringBuilder nameOrDefinitionChangeStringBuilder = new StringBuilder();
	private StringBuilder categoryMismatchStringBuilder = new StringBuilder();
	private StringBuilder obsoletionStringBuilder = new StringBuilder();
	private StringBuilder newGOTermStringBuilder = new StringBuilder();
	private StringBuilder deletionStringBuilder = new StringBuilder();
	private StringBuilder updatedRelationshipStringBuilder = new StringBuilder();
	
	private StringBuilder mainOutput = new StringBuilder();
	
	public GoTermsUpdater(MySQLAdaptor dba, List<String> goLines, List<String> ec2GoLines)
	{
		this.adaptor = dba;
		this.goLines = goLines;
		this.ec2GoLines = ec2GoLines;
		
	}
	
	/**
	 * Executes the GO Terms updates. Returns a StringBuilder, which contains a report about what happened.
	 * @return
	 */
	public StringBuilder updateGoTerms()
	{
		// This map is keyed by GO ID. Values are maps of strings that map to values from the file.
		Map<String, Map<String,Object>> goTerms = new HashMap<String, Map<String,Object>>();
		// A list of mapping "alt_id" GO IDs to primary GO IDs. Used when re-mapping references to deleted GO Terms.
		Map<String, List<String>> altGoIdToMainGoId = new HashMap<String, List<String>>();
		// This map is keyed by GO Accession number (GO ID).
		Map<String, List<GKInstance>> allGoInstances = populateMapOfAllGOInstances(adaptor);
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
		
		GKInstance goRefDB = null;
		try
		{
			goRefDB = ((Set<GKInstance>) adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=","GO")).stream().findFirst().get();
			System.out.println("RefDB for GO: "+goRefDB.toString());
		}
		catch (Exception e1)
		{
			System.out.println("Couldn't even get a reference to the GO ReferenceDatabase object. There's no point in continuing, so this progam will exit. :(");
			e1.printStackTrace();
			System.exit(1);
		}
		
		for (String line : this.goLines)
		{
			lineCount ++;
			// Empty line means end of a Term.
			if (line.trim().isEmpty())
			{
				// The first time we get here, is an empty line after the header but before the first GO term.
				if (!currentGOID.trim().isEmpty())
				{
					termStarted = false;
					// Now we need to process the Term that was just finished.
					List<GKInstance> goInstances = allGoInstances.get(currentGOID);
					// First let's make sure the GO Term is not obsolete.
					if ( !goTerms.get(currentGOID).containsKey(GoUpdateConstants.IS_OBSOLETE) && !goTerms.get(currentGOID).containsKey(GoUpdateConstants.PENDING_OBSOLETION))
					{
						if (goInstances==null)
						{
							// Create a new Instance if there is nothing in the current list of instances.
							//System.out.println("New GO Term to create: GO:"+ currentGOID + " " + goTerms.get(currentGOID));
							newGOTermStringBuilder.append("New GO Term to create: GO:").append(currentGOID).append(" ").append(goTerms.get(currentGOID)).append("\n");
							
							
							createNewGOTerm(adaptor, goRefDB, goTerms, goToECNumbers, currentGOID, currentCategory);
							
							newGoTermCount++;
						}
						else
						{
							// Try to update each goInstance that has the current GO ID.
							for (GKInstance goInst : goInstances)
							{
								currentCategory = alignCategoryName(currentCategory);
								// Compartment is a sub-class of GO_CellularComponent - but the GO namespaces don't seem to account for that,
								// we we'll account for that here.
								if (goInst.getSchemClass().getName().equals(currentCategory) 
									|| ( goInst.getSchemClass().getName().equals(ReactomeJavaConstants.Compartment) && currentCategory.equals(ReactomeJavaConstants.GO_CellularComponent)))
								{
									//Now do the update.
									updateGOInstance(adaptor, goTerms, goToECNumbers, currentGOID, currentDefinition, goInst);
								}
								else
								{
									mismatchCount++;
									//System.out.println("Category mismatch! GO ID: "+currentGOID+" Category in DB: "+goInst.getSchemClass().getName()+ " category in GO file: "+currentCategory);
									categoryMismatchStringBuilder.append("Category mismatch! GO ID: ").append(currentGOID).append(" Category in DB: ").append(goInst.getSchemClass().getName()).append(" category in GO file: ").append(currentCategory).append("\n");
									//deleteGoInstance(goInst, goTerms, adaptor);
									instancesForDeletion.add(goInst);
								}
							}
						}
					}
					else if (goTerms.get(currentGOID).containsKey(GoUpdateConstants.PENDING_OBSOLETION) && goTerms.get(currentGOID).get(GoUpdateConstants.PENDING_OBSOLETION).equals(true))
					{
						// If we have this in our database, it must be reported!
						if (goInstances!=null)
						{
							pendingObsoleteCount++;
							obsoletionStringBuilder.append("GO Instance ").append(goInstances.toString()).append(" are marked as PENDING obsolete!\n");
						}
					}
					else if (goTerms.get(currentGOID).containsKey(GoUpdateConstants.IS_OBSOLETE) && goTerms.get(currentGOID).get(GoUpdateConstants.IS_OBSOLETE).equals(true))
					{
						// If we have this in our database, it must be reported!
						if (goInstances!=null)
						{
							obsoleteCount++;
							obsoletionStringBuilder.append("GO Instance ").append(goInstances.toString()).append(" are marked as OBSOLETE!\n");
							for (GKInstance inst : goInstances)
							{
								//deleteGoInstance(inst, goTerms, adaptor);
								instancesForDeletion.add(inst);
							}
						}
						
					}
					// else { // ... what? it has one of the OBSOLETE Keys but no value? That wouldn't make sense...
					
				}
			}
			// We are starting a new Term.
			else if (line.equals("[Term]"))
			{
				termStarted = true;
				goTermCount++;
			}
			else if (termStarted)
			{
				processLine(line, goTerms, altGoIdToMainGoId);
			}
		}
		
		// Now that the full goTerms structure is complete, and the alternate GO IDs are set up, we can delete the obsolete/category-mismatched GO instances from the database.
		for (GKInstance instance : instancesForDeletion)
		{
			deleteGoInstance(instance, goTerms, altGoIdToMainGoId, allGoInstances, adaptor);
		}
		//Reload the list of GO Instances, since new ones have been created, and old ones have been deleted.
		allGoInstances = populateMapOfAllGOInstances(adaptor);
		// Now that the main loop has run, update relationships between GO terms.
		for (String goId : goTerms.keySet())
		{
			List<GKInstance> goInsts = (List<GKInstance>) allGoInstances.get(goId);
			Map<String, Object> goProps = goTerms.get(goId);
			if (goInsts != null && !goInsts.isEmpty())
			{
				for (GKInstance goInst : goInsts)
				{
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.IS_A, ReactomeJavaConstants.instanceOf);
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.HAS_PART, "hasPart");
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.PART_OF, ReactomeJavaConstants.componentOf);
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.REGULATES, "regulate");
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.POSITIVELY_REGULATES, "positivelyRegulate");
					updateRelationship(adaptor, allGoInstances, goInst, goProps, GoUpdateConstants.NEGATIVELY_REGULATES, "negativelyRegulate");
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
	 * @param adaptor
	 * @return
	 */
	private Map<String, List<GKInstance>> populateMapOfAllGOInstances(MySQLAdaptor adaptor)
	{
		Collection<GKInstance> bioProcesses = new ArrayList<GKInstance>();
		Collection<GKInstance> molecularFunctions = new ArrayList<GKInstance>();
		Collection<GKInstance> cellComponents = new ArrayList<GKInstance>();
		try
		{
			bioProcesses = adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
			System.out.println(bioProcesses.size() + " GO_BiologicalProcesses in the database.");
			molecularFunctions = adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
			System.out.println(molecularFunctions.size() + " GO_MolecularFunction in the database.");
			cellComponents = adaptor.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);
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
	 * 
	 * @param adaptor
	 * @param goRefDB
	 * @param goTerms
	 * @param goToEcNumbers
	 * @param currentGOID
	 * @param currentCategory
	 */
	private void createNewGOTerm(MySQLAdaptor adaptor, GKInstance goRefDB, Map<String, Map<String, Object>> goTerms, Map<String,List<String>> goToEcNumbers, String currentGOID, String currentCategory)
	{
		currentCategory = alignCategoryName(currentCategory);
		SchemaClass schemaClass = adaptor.getSchema().getClassByName(currentCategory);
		GKInstance newGOTerm = new GKInstance(schemaClass);
		try
		{
			newGOTerm.setAttributeValue(ReactomeJavaConstants.accession, currentGOID);
			newGOTerm.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(GoUpdateConstants.NAME));
			newGOTerm.setAttributeValue(ReactomeJavaConstants.definition, goTerms.get(currentGOID).get(GoUpdateConstants.DEF));
			newGOTerm.setAttributeValue(ReactomeJavaConstants.referenceDatabase, goRefDB);
			if (schemaClass.getName().equals(ReactomeJavaConstants.GO_MolecularFunction))
			{
				List<String> ecNumbers = goToEcNumbers.get(currentGOID);
				if (ecNumbers!=null)
				{
					for (String ecNumber : ecNumbers)
					{
						newGOTerm.setAttributeValue(ReactomeJavaConstants.ecNumber, ecNumber);
					}
				}
			}
			InstanceDisplayNameGenerator.setDisplayName(newGOTerm);
			// TODO: Set Created and Modified.
			newGOTerm.setDbAdaptor(adaptor);
			adaptor.storeInstance(newGOTerm);
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			System.err.println("Attribute/value error! "+ e.getMessage());
			e.printStackTrace();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	/**
	 * 
	 * @param currentCategory
	 * @return
	 */
	private String alignCategoryName(String currentCategory)
	{
		// Aling the "category" from the GO file with the schema class name from Reactome.
		switch (currentCategory)
		{
			case "biological_process":
				currentCategory = ReactomeJavaConstants.GO_BiologicalProcess;
				break;
			case "molecular_function":
				currentCategory = ReactomeJavaConstants.GO_MolecularFunction;
				break;
			case "cellular_component":
				currentCategory = ReactomeJavaConstants.GO_CellularComponent;
				break;
		}
		return currentCategory;
	}
	
	/**
	 * 
	 * @param adaptor
	 * @param goTerms
	 * @param goToEcNumbers
	 * @param currentGOID
	 * @param currentDefinition
	 * @param goInst
	 */
	private void updateGOInstance(MySQLAdaptor adaptor, Map<String, Map<String, Object>> goTerms, Map<String, List<String>> goToEcNumbers, String currentGOID, String currentDefinition, GKInstance goInst)
	{
		try
		{
			// according to the logic in the Perl code, if the existing name does not
			// match the name in the file or if the existing definition does not match
			// the one in the file, we update with the new name and def'n, and then set
			// InstanceOf and ComponentOf to NULL, and I guess those get updated later.
			if ((goTerms.get(currentGOID).get(GoUpdateConstants.NAME)!=null && !goTerms.get(currentGOID).get(GoUpdateConstants.NAME).equals(goInst.getAttributeValue(ReactomeJavaConstants.name)))
				|| (currentDefinition != null && !currentDefinition.equals(goInst.getAttributeValue(ReactomeJavaConstants.definition))))
			{
//				nameOrDefinitionChangeStringBuilder.append("Change in name/definition for GO ID ").append(currentGOID).append("! ")
//						.append("New name: \"").append(goTerms.get(currentGOID).get(GoUpdateConstants.NAME)).append("\" vs. old name: \"").append(goInst.getAttributeValue(ReactomeJavaConstants.name)).append("\"")
//						.append(" new def'n: \"").append(currentDefinition).append("\" vs old def'n: \"").append(goInst.getAttributeValue(ReactomeJavaConstants.definition)).append("\". ")
//						.append("  instanceOf and componentOf fields will be cleared (and hopefully reset later in the process)\n");
				goInst.setAttributeValue(ReactomeJavaConstants.instanceOf, null);
				adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.instanceOf);
				goInst.setAttributeValue(ReactomeJavaConstants.componentOf, null);
				adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.componentOf);
				
				goInst.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(GoUpdateConstants.NAME));
				adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.name);
				goInst.setAttributeValue(ReactomeJavaConstants.definition, currentDefinition);
				adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.definition);
			}
			
			if (goInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction))
			{
				List<String> ecNumbers = goToEcNumbers.get(currentGOID);
				if (ecNumbers!=null)
				{
					goInst.setAttributeValue(ReactomeJavaConstants.ecNumber, null);
					for (String ecNumber : ecNumbers)
					{
						goInst.addAttributeValue(ReactomeJavaConstants.ecNumber, ecNumber);
						//nameOrDefinitionChangeStringBuilder.append("GO Term (").append(currentGOID).append(") has new EC Number: ").append(ecNumber).append("\n");
					}
					adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.ecNumber);
				}
			}
			
			InstanceDisplayNameGenerator.setDisplayName(goInst);
			adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants._displayName);
			
			//TODO: add "modified" InstanceEdit.
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			System.err.println("Attribute/Value problem with "+goInst.toString()+ " " + e.getMessage());
			e.printStackTrace();
		}
		catch (NullPointerException e)
		{
			System.err.println("NullPointerException occurred! GO ID: "+currentGOID+" GO Instance: "+goInst + " GO Term: "+goTerms.get(currentGOID));
			e.printStackTrace();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	/**
	 * 
	 * @param goInst
	 * @param goTerms
	 * @param altGoIDsToMainGoIDs
	 * @param allGoInstances
	 * @param adaptor
	 */
	private void deleteGoInstance(GKInstance goInst, Map<String, Map<String,Object>> goTerms, Map<String, List<String>> altGoIDsToMainGoIDs, Map<String, List<GKInstance>> allGoInstances, MySQLAdaptor adaptor)
	{
		try
		{
			String goId = (String) goInst.getAttributeValue(ReactomeJavaConstants.accession);
			deletionStringBuilder.append("Deleting GO instance: \"").append(goInst.toString()).append("\" (GO:").append(goId).append(")\n");
			// before we do the actual delete, we should update referrers to refer to a GO Term whose *alternate* accession (GO ID) is the id of the 
			// term being deleted.
			String altGoId = null;
			if (goTerms.get(goId).get(GoUpdateConstants.REPLACED_BY)!=null)
			{
				altGoId = ((List<String>)goTerms.get(goId).get(GoUpdateConstants.REPLACED_BY)).get(0);
			}
			else if (goTerms.get(goId).get(GoUpdateConstants.CONSIDER)!=null)
			{
				altGoId = ((List<String>)goTerms.get(goId).get(GoUpdateConstants.CONSIDER)).get(0);
			}
			else if (goTerms.get(goId).get(GoUpdateConstants.ALT_ID)!=null)
			{
				altGoId = ((List<String>)goTerms.get(goId).get(GoUpdateConstants.ALT_ID)).get(0);
			}
			
			
			//if (altGoIDsToMainGoIDs.containsKey(goId)) //TODO: remove altGoIDsToMainGoIDs
			if (altGoId != null)
			{
				// The current instances GO ID is an alternate to others. So, we will re-direct referrers to that one.
				// If there's more than one, just use the first one.
				String replacementGoId = altGoId; //altGoIDsToMainGoIDs.get(goId).get(0);
				if (allGoInstances.containsKey(replacementGoId))
				{
					GKInstance replacementGoInstance = allGoInstances.get(replacementGoId).get(0);
					@SuppressWarnings("unchecked")
					Map<String, List<GKInstance>> referrers = new HashMap<String, List<GKInstance>>();
					for (String attribute : Arrays.asList(ReactomeJavaConstants.activity, "componentOf", "hasPart", "negativelyRegulate", "positivelyRegulat", "regulate"))
					{
						@SuppressWarnings("unchecked")
						List<GKInstance> tmp = (List<GKInstance>) goInst.getReferers(attribute);
						if (tmp!=null)
						{
							referrers.put(attribute, tmp );
						}
					}
					
					// for each of goInst's referrers, redirect them to the replacement instance.
					for (String attribute : referrers.keySet())
					{
						for (GKInstance referringInstance : referrers.get(attribute))
						{
							GKInstance tmp = (GKInstance) referringInstance.getAttributeValue(attribute);
							if (tmp.getDBID() == goInst.getDBID())
							{
								deletionStringBuilder.append("\"").append(referringInstance.toString()).append("\" now refers to \"").append(replacementGoInstance).append("\" (GO:").append(replacementGoId).append(") via \"").append(attribute).append("\"");
								referringInstance.setAttributeValue(attribute, replacementGoInstance);
								adaptor.updateInstanceAttribute(referringInstance, attribute);
							}
						}
					}
				}
				else
				{
					//TODO: when logging with log4j, log this as a WARNING!
					deletionStringBuilder.append("Replacement GO Instance with GO ID: ").append(replacementGoId).append(" could not be found in allGoInstances map.")
							.append("This was not expected. Instance \"").append(goInst.toString()).append("\" will still be deleted but referrs will have nothing to refer to.\n");
				}
			}
			adaptor.deleteInstance(goInst);
		}
		catch (Exception e)
		{
			System.err.println("Error occurred while trying to delete instance: \""+goInst.toString()+"\": "+e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @param line
	 * @param goTerms
	 * @param altGoIdToMainGoId
	 */
	private void processLine(String line, Map<String, Map<String,Object>> goTerms, Map<String,List<String>> altGoIdToMainGoId)
	{
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
						String goID = m.matches() ? m.group(1) : "";
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
								// TODO: Use Log4j2 properly.
								System.out.println("GO ID " + goID + " has appeared more than once in the input!");
								// TODO: exit is probably not the best way to handle this. only for early-development debugging...
								System.exit(1);
							}
						}
						break;
					}
					case GoUpdateConstants.ALT_ID:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.ALT_ID_REGEX, GoUpdateConstants.ALT_ID);
						updateAltGoIDsList(goTerms, altGoIdToMainGoId, GoUpdateConstants.ALT_ID);
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
								System.out.println("GO ID " + currentGOID + " already has a value for NAME - and this is a single-value field!");
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
							currentCategory = namespace;
						}
						break;
					}
					case GoUpdateConstants.DEF:
					{
						m = GoUpdateConstants.DEF_REGEX.matcher(line);
						String def = m.matches() ? m.group(1) : "";
						if (!def.trim().isEmpty())
						{
							currentDefinition = def;
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
						updateAltGoIDsList(goTerms, altGoIdToMainGoId, GoUpdateConstants.CONSIDER);
						break;
					}
					case GoUpdateConstants.REPLACED_BY:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, GoUpdateConstants.REPLACED_BY_REGEX, GoUpdateConstants.REPLACED_BY);
						updateAltGoIDsList(goTerms, altGoIdToMainGoId, GoUpdateConstants.REPLACED_BY);
						break;
					}
					case GoUpdateConstants.IS_OBSOLETE:
					{
						m = GoUpdateConstants.IS_OBSOLETE_REGEX.matcher(line);
						if (m.matches())
						{
							goTerms.get(currentGOID).put(GoUpdateConstants.IS_OBSOLETE, true);
						}
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
						// ...such as...
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
	}

	/**
	 * 
	 * @param goTerms
	 * @param currentGOID
	 * @param line
	 * @param pattern
	 * @param key
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
	 * @param goTerms
	 * @param altGoIdToMainGoId
	 */
	private void updateAltGoIDsList(Map<String, Map<String, Object>> goTerms, Map<String, List<String>> altGoIdToMainGoId, String key) {
		@SuppressWarnings("unchecked")
		List<String> altGoIds = (List<String>) goTerms.get(currentGOID).get(key);
		// Build a mapping that maps alternate GO IDs to primary GO IDs.
		for (String altGoId : altGoIds)
		{
			if (altGoIdToMainGoId.containsKey(altGoId))
			{
				altGoIdToMainGoId.get(altGoId).add(currentGOID);
			}
			else
			{
				List<String> primaryGoIds = new ArrayList<String>();
				primaryGoIds.add(currentGOID);
				altGoIdToMainGoId.put(altGoId, primaryGoIds);
			}
		}
	}

	/**
	 * @param adaptor
	 * @param allGoInstances
	 * @param goInst
	 * @param goProps
	 */
	private void updateRelationship(MySQLAdaptor adaptor, Map<String, List<GKInstance>> allGoInstances, GKInstance goInst, Map<String, Object> goProps, String relationshipKey, String reactomeRelationshipName)
	{
		if (goProps.containsKey(relationshipKey))
		{
			@SuppressWarnings("unchecked")
			List<String> otherIDs = (List<String>) goProps.get(relationshipKey);
			for (String otherID : otherIDs)
			{				
				List<GKInstance> otherInsts = allGoInstances.get(otherID);
				try
				{
					goInst.getAttributeValuesList(reactomeRelationshipName);
					if (otherInsts != null && !otherInsts.isEmpty())
					{
						for (GKInstance inst : otherInsts)
						{
							try
							{
								goInst.addAttributeValue(reactomeRelationshipName, inst);
								//System.out.println("Relationship updated! \""+goInst.toString()+"\" (GO:"+goInst.getAttributeValue(ReactomeJavaConstants.accession)+" now has relationship \""+reactomeRelationshipName+"\" referring to \""+inst.toString()+"\" (GO:"+inst.getAttributeValue(ReactomeJavaConstants.accession)+")");
								updatedRelationshipStringBuilder.append("Relationship updated! \"").append(goInst.toString()).append("\" (GO:").append(goInst.getAttributeValue(ReactomeJavaConstants.accession))
								.append(") now has relationship \"").append(reactomeRelationshipName).append("\" referring to \"").append(inst.toString()).append("\" (GO:")
								.append(inst.getAttributeValue(ReactomeJavaConstants.accession)).append(")\n");
							}
							catch (InvalidAttributeValueException e)
							{
								System.err.println("InvalidAttributeValueException was caught! Instance was \""+goInst.toString()+"\" with GO ID: "+goInst.getAttributeValue(ReactomeJavaConstants.accession)+", attribute was: "+reactomeRelationshipName+ ", Value was: \""+inst+"\", with GO ID: "+inst.getAttributeValue(ReactomeJavaConstants.accession));
								e.printStackTrace();
							}
						}
					}
					else
					{
						System.out.println("Could not find instance with GO ID "+otherID);
					}
					adaptor.updateInstanceAttribute(goInst, reactomeRelationshipName);
				}
				
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		}
	}
	
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
