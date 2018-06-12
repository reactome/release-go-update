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
public class Main {

	private static final String ID = "id";
	private static final String ALT_ID = "alt_id";
	private static final String NAME = "name";
	private static final String NAMESPACE = "namespace";
	private static final String DEF = "def";
	//private static final String SUBSET = "subset";
	private static final String RELATIONSHIP = "relationship";
	private static final String IS_A = "is_a";
	private static final String CONSIDER = "consider";
	private static final String REPLACED_BY = "replaced_by";
	private static final String SYNONYM = "synonym";
	private static final String HAS_PART = "has_part";
	private static final String PART_OF = "part_of";
	private static final String REGULATES = "regulates";
	private static final String POSITIVELY_REGULATES = "positively_"+REGULATES;
	private static final String NEGATIVELY_REGULATES = "negatively_"+REGULATES;
	private static final String IS_OBSOLETE = "is_obsolete";
	private static final String PENDING_OBSOLETION = "pending_obsoletion";
	
	private static final Pattern LINE_DECODER = Pattern.compile("^(id|alt_id|name|namespace|def|subset|relationship|is_a|consider|replaced_by|synonym|is_obsolete):.*");
	private static final Pattern RELATIONSHIP_DECODER = Pattern.compile("^relationship: (positively_regulates|negatively_regulates|has_part|part_of|regulates) GO:[0-9]+");
	private static final Pattern OBSOLETION = Pattern.compile("(pending|scheduled for|slated for) obsoletion");
	private static final Pattern IS_OBSOLETE_REGEX = Pattern.compile("^"+IS_OBSOLETE+": true");
	private static final Pattern GO_ID_REGEX = Pattern.compile("^"+ID+": GO:([0-9]+)");
	private static final Pattern ALT_ID_REGEX = Pattern.compile("^"+ALT_ID+": GO:([0-9]+)");
	private static final Pattern NAME_REGEX = Pattern.compile("^"+NAME+": (.*)");
	private static final Pattern NAMESPACE_REGEX = Pattern.compile("^"+NAMESPACE+": ([a-zA-Z_]*)");
	private static final Pattern DEF_REGEX = Pattern.compile("^"+DEF+": (.*)");
	private static final Pattern IS_A_REGEX = Pattern.compile("^"+IS_A+": GO:([0-9]+)");
	private static final Pattern SYNONYM_REGEX = Pattern.compile("^"+SYNONYM+": (.*)");
	private static final Pattern CONSIDER_REGEX = Pattern.compile("^"+CONSIDER+": GO:([0-9]+)");
	private static final Pattern REPLACED_BY_REGEX = Pattern.compile("^"+REPLACED_BY+": GO:([0-9]+)");
	private static final Pattern RELATIONSHIP_PART_OF_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+PART_OF+" GO:([0-9]+)");
	private static final Pattern RELATIONSHIP_HAS_PART_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+HAS_PART+" GO:([0-9]+)");
	private static final Pattern RELATIONSHIP_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+REGULATES+" GO:([0-9]+)");
	private static final Pattern RELATIONSHIP_POSITIVELY_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+POSITIVELY_REGULATES+" GO:([0-9]+)");
	private static final Pattern RELATIONSHIP_NEGATIVELY_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+NEGATIVELY_REGULATES+" GO:([0-9]+)");

	
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
						if ( !goTerms.get(currentGOID).containsKey(IS_OBSOLETE) && !goTerms.get(currentGOID).containsKey(PENDING_OBSOLETION))
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
						else if (goTerms.get(currentGOID).containsKey(PENDING_OBSOLETION) && goTerms.get(currentGOID).get(PENDING_OBSOLETION).equals(true))
						{
							// If we have this in our database, it must be reported!
							if (goInstances!=null)
							{
								pendingObsoleteCount++;
								System.out.println("GO Instance "+goInstances.toString() + " are marked as PENDING obsolete!");
							}
						}
						else if (goTerms.get(currentGOID).containsKey(IS_OBSOLETE) && goTerms.get(currentGOID).get(IS_OBSOLETE).equals(true))
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
			newGOTerm.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(NAME));
			newGOTerm.setAttributeValue(ReactomeJavaConstants.definition, goTerms.get(currentGOID).get(DEF));
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
			
			goInst.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(NAME));
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
			m = LINE_DECODER.matcher(line);
			if (m.matches())
			{
				String lineCode = m.group(1);
				switch (lineCode)
				{
					case ID:
					{
						m = GO_ID_REGEX.matcher(line);
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
					case ALT_ID:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, ALT_ID_REGEX, ALT_ID);
						break;
					}
					case NAME:
					{
						m = NAME_REGEX.matcher(line);
						String name = m.matches() ? m.group(1) : "";
						if (!name.trim().isEmpty())
						{
							if (!goTerms.get(currentGOID).containsKey(name))
							{
								goTerms.get(currentGOID).put(NAME, name);
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
					case NAMESPACE:
					{
						m = NAMESPACE_REGEX.matcher(line);
						String namespace = m.matches() ? m.group(1) : "";
						if (!namespace.trim().isEmpty())
						{
							currentCategory = namespace;
						}
						break;
					}
					case DEF:
					{
						m = DEF_REGEX.matcher(line);
						String def = m.matches() ? m.group(1) : "";
						if (!def.trim().isEmpty())
						{
							currentDefinition = def;
						}
						break;
					}
					case IS_A:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, IS_A_REGEX, IS_A);
						break;
					}
					case SYNONYM:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, SYNONYM_REGEX, SYNONYM);
						break;
					}
					case CONSIDER:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, CONSIDER_REGEX, CONSIDER);
						break;
					}
					case REPLACED_BY:
					{
						addToMultivaluedAttribute(goTerms, currentGOID, line, REPLACED_BY_REGEX, REPLACED_BY);
						break;
					}
					case IS_OBSOLETE:
					{
						m = IS_OBSOLETE_REGEX.matcher(line);
						if (m.matches())
						{
							goTerms.get(currentGOID).put(IS_OBSOLETE, true);
						}
					}
					case RELATIONSHIP:
					{
						m = RELATIONSHIP_DECODER.matcher(line);
						if (m.matches())
						{
							String relationShipType = m.group(1);
							switch (relationShipType)
							{
								case HAS_PART:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, RELATIONSHIP_HAS_PART_REGEX, HAS_PART);
									break;
								}
								case PART_OF:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, RELATIONSHIP_PART_OF_REGEX, PART_OF);
									break;
								}
								case REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, RELATIONSHIP_REGULATES_REGEX, REGULATES);
									break;
								}
								case POSITIVELY_REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, RELATIONSHIP_POSITIVELY_REGULATES_REGEX, POSITIVELY_REGULATES);
									break;
								}
								case NEGATIVELY_REGULATES:
								{
									addToMultivaluedAttribute(goTerms, currentGOID, line, RELATIONSHIP_NEGATIVELY_REGULATES_REGEX, NEGATIVELY_REGULATES);
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
						m = OBSOLETION.matcher(line);
						if (m.matches())
						{
							goTerms.get(currentGOID).put(PENDING_OBSOLETION, true);
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



