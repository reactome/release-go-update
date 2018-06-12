/**
 * 
 */
package org.reactome.goupdate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
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
import org.gk.persistence.TransactionsNotSupportedException;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.gk.schema.SchemaClass;

/**
 * @author sshorser
 *
 */
public class Main
{
	private static String currentGOID = "";
	private static String currentCategory = "";
	private static String currentDefinition = "";
	/**
	 * @param args
	 * @throws SQLException 
	 */
	public static void main(String[] args) throws SQLException
	{
		// First part:
		// 1) Get the GO files:
		// - http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo
		// - http://geneontology.org/external2go/ec2go
		// 2) from database, get list of all things where:
		//    biological_process=GO_BiologicalProcess, molecular_function=GO_MolecularFunction, cellular_component=GO_CellularComponent 
		// 3) Read gene_ontology_ext.obo
		// 4) Update objects from Database based on GO file.
		// 5) print Wiki output.
		//
		// Second part:
		// 1) Read ec2go file
		// 2) extact EC to GO mapping.
		// 3) Update GO objects in Database.
		//
		// ...Of course, we could just do these together in one program: Read both files and populate one data structure containing everything.
		
		String pathToGOFile = "src/main/resources/gene_ontology_ext.obo";
		String pathToEC2GOFile = "src/main/resources/ec2go";
		boolean termStarted = false; 
		try
		{
			MySQLAdaptor adaptor = new MySQLAdaptor("localhost","gk_central","root","root",3306);
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
			try
			{
				adaptor.startTransaction();
			}
			catch (TransactionsNotSupportedException e1)
			{
				e1.printStackTrace();
				System.out.println("This program should run within a transaction. Aborting.");
				System.exit(1);
			}
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
			Consumer<? super GKInstance> populateAllGoInstMap = inst -> {
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
			
			bioProcesses.forEach( populateAllGoInstMap);
			
			cellComponents.forEach( populateAllGoInstMap);
			
			molecularFunctions.forEach( populateAllGoInstMap);
			
			System.out.println(allGoInstances.size() + " items in the allGoInstances map.");
			
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			Map<String, Map<String,Object>> goTerms = new HashMap<String, Map<String,Object>>();
			int lineCount = 0;
			int newGoTermCount = 0;
			int obsoleteCount = 0;
			int pendingObsoleteCount = 0;
			int mismatchCount = 0;
			for (String line : goLines)
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
								System.out.println("New GO Term to create: GO:"+ currentGOID + " " + goTerms.get(currentGOID));
								
								
								createNewGOTerm(adaptor, goRefDB, goTerms, currentGOID, currentCategory);
								
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
										updateGOInstance(adaptor, goTerms, currentGOID, currentDefinition, goInst);
									}
									else
									{
										mismatchCount++;
										System.out.println("Category mismatch! GO ID: "+currentGOID+" Category in DB: "+goInst.getSchemClass().getName()+ " category in GO file: "+currentCategory);
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
								System.out.println("GO Instance "+goInstances.toString() + " are marked as PENDING obsolete!");
							}
						}
						else if (goTerms.get(currentGOID).containsKey(GoUpdateConstants.IS_OBSOLETE) && goTerms.get(currentGOID).get(GoUpdateConstants.IS_OBSOLETE).equals(true))
						{
							// If we have this in our database, it must be reported!
							if (goInstances!=null)
							{
								obsoleteCount++;
								System.out.println("GO Instance "+goInstances.toString() + " are marked as OBSOLETE!");
							}
							
						}
						// else { // ... what? it has one of the OBSOLETE Keys but no value? That wouldn't make sense...
						
					}
				}
				// We are starting a new Term.
				else if (line.equals("[Term]"))
				{
					termStarted = true;
				}
				else if (termStarted)
				{
					processLine(line, goTerms);
				}
			}
			System.out.println(goTerms.size() + " GO terms were read from the file.");
			System.out.println(lineCount + " lines were processed.");
			System.out.println(newGoTermCount + " new GO terms.");
			System.out.println(mismatchCount + " had mismatched categories.");
			System.out.println(obsoleteCount + " are obsolete.");
			System.out.println(pendingObsoleteCount + " are pending obsolescence.");
			
			adaptor.rollback();	
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		
	}

	private static void createNewGOTerm(MySQLAdaptor adaptor, GKInstance goRefDB, Map<String, Map<String, Object>> goTerms, String currentGOID, String currentCategory)
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
			InstanceDisplayNameGenerator.setDisplayName(newGOTerm);
			// TODO: Set Created and Modified.
			//adaptor.storeInstance(newGOTerm);
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			e.printStackTrace();
		}
	}

	private static String alignCategoryName(String currentCategory)
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

	private static void updateGOInstance(MySQLAdaptor adaptor, Map<String, Map<String, Object>> goTerms, String currentGOID, String currentDefinition, GKInstance goInst)
	{
		try
		{
			// according to the logic in the Perl code, if the existing name does not
			// match the name in the file or if the existing definition does not match
			// the one in the file, we update with the new name and def'n, and then set
			// InstanceOf and ComponentOf to NULL, and I guess those get updated later.
			
			goInst.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(GoUpdateConstants.NAME));
//			adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.name);
			
			goInst.setAttributeValue(ReactomeJavaConstants.definition, currentDefinition);
//			adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants.definition);
			
			InstanceDisplayNameGenerator.setDisplayName(goInst);
//			adaptor.updateInstanceAttribute(goInst, ReactomeJavaConstants._displayName);
			//TODO: add "modified" InstanceEdit.
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			e.printStackTrace();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private static void addToMultivaluedAttribute(Map<String, Map<String, Object>> goTerms, String currentGOID, String line, Pattern pattern, String key) {
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
	
	private static void processLine(String line, Map<String, Map<String,Object>> goTerms)
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

}



