/**
 * 
 */
package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author sshorser
 *
 */
class GoLineProcessor
{
	private static final Logger logger = LogManager.getLogger();
	
	private GoLineProcessor() {}
	
	/**
	 * Process a line from the GO file.
	 * @param line - The line.
	 * @param goTerms - The GO terms map. This map will be updated by this function.
	 * @param currentGOID - The ID of the GO term currently being processed, line by line.
	 * @return The GO Accession of the GO term currently being processed. Will be different from <code>currentGOID</code> if the ID for a new GO term is seen on this <code>line</code>
	 */
	static String processLine(String line, String currentGOID, Map<String, Map<String, Object>> goTerms)
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
								// If a GO ID appears a second time, an error message will be logged and a RuntimeException will
								// be thrown. This should not happen, and there is currently no defined way to decide which duplicate
								// to use and which to discard. Or should they be merged? And what if one duplicate is marked as obsolete
								// and the other is not? A RuntimeException should break the program's execution and force the user
								// to verify that the file is OK. I guess If GO one day decides that duplicates are OK, then this code will
								// need to be rewritten, but for now, that is not the case.
								logger.error("GO ID {} has appeared more than once in the input! This is highly unexpected. "
										+ "Please verify the contents of this file. "
										+ "You should check that you are using a fresh GO file. "
										+ "If using a new file from GO *still* causes this error, consider reporting this issue to GO.", goID);
								throw new RuntimeException("Duplicate GO ID (GO:"+goID+") in input file. This should not happen. Please verify file and try again. Aborting.");
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
								logger.error("GO ID {} *already* has a value for NAME ({}) - and this is a single-value field!", currentGOID, goTerms.get(currentGOID).get(name));
								// TODO: exit is probably not the best way to handle this. only for early-development debugging...
								// System.exit(1);
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
							}
						}
						break;
					}
				}
			}
			else
			{
				// Obsoletion doesn't matche the line decoder regexp, so we need to test it separately.
				m = GoUpdateConstants.OBSOLETION.matcher(line);
				if (m.matches())
				{
					goTerms.get(currentGOID).put(GoUpdateConstants.PENDING_OBSOLETION, true);
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
	static private void addToMultivaluedAttribute(Map<String, Map<String, Object>> goTerms, String currentGOID, String line, Pattern pattern, String key)
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
}
