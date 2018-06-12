/**
 * 
 */
package org.reactome.goupdate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

	/**
	 * @param args
	 */
	public static void main(String[] args)
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
		
		// Second part:
		// 1) Read ec2go file
		// 2) extact EC to GO mapping.
		// 3) Update GO objects in Database.
		
		// Of course, we could just do these together: Read both files and populate one data structure containing everything.
		
		String pathToGOFile = "src/main/resources/gene_ontology_ext.obo";
		String pathToEC2GOFile = "src/main/resources/ec2go";
		boolean termStarted = false; 
		try
		{
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			Map<String, Map<String,Object>> goTerms = new HashMap<String, Map<String,Object>>();
			int lineCount = 0;
			String currentGOID = "";
			String currentCategory = "";
			String currentDefinition = "";
			for (String line : goLines)
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
				}
				else if (termStarted)
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
					catch (IllegalStateException e)
					{
						if (!e.getMessage().equals("No match found"))
						{
							// no match found is OK, but anything else should be raised.
							throw e;
						}
					}
				}
				
			}
			System.out.println(goTerms.size() + " GO terms were read from the file.");
			System.out.println(lineCount + " lines were processed.");
			
			// Now, update the objects in the database.
			for (String goId : goTerms.keySet()/*.stream().sorted().collect(Collectors.toList())*/)
			{
				System.out.println(goId + ": " + goTerms.get(goId).toString());
			}
		}
		catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static void addToMultivaluedAttribute(Map<String, Map<String, Object>> goTerms, String currentGOID, String line, Pattern pattern, String key) {
		Matcher m;
		m = pattern.matcher(line);
		String extractedValue = m.matches() ? m.group(1) : "";
		if (!extractedValue.trim().isEmpty())
		{
			@SuppressWarnings("unchecked")
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

}



