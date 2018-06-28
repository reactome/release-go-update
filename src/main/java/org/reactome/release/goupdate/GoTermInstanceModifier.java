package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.gk.schema.SchemaClass;

/**
 * This class is responsible for creating/modifying/deleting a single GO term (as a GKInstance) in the database.
 * @author sshorser
 *
 */
class GoTermInstanceModifier
{

	private static final Logger logger = LogManager.getLogger("GoUpdateLogger");
	private MySQLAdaptor adaptor;
	private GKInstance goInstance;
	private GKInstance instanceEdit;
	
	/**
	 * Create the data modifier that is suitable for creating updating or deleting existing GO terms in the database.
	 * @param adaptor - the database adaptor to use.
	 * @param goInstance - the GKInstance for the GO term you wish to update/delete.
	 * @param instanceEdit - the InstanceEdit that the data modification should be associated with.
	 */
	public GoTermInstanceModifier(MySQLAdaptor adaptor, GKInstance goInstance, GKInstance instanceEdit)
	{
		this.adaptor = adaptor;
		this.goInstance = goInstance;
		this.instanceEdit = instanceEdit;
	}
	
	/**
	 * Create a data modifier that is suitable for creating *new* GO terms in the database.
	 * @param adaptor - the database adaptor to use.
	 * @param instanceEdit - the InstanceEdit that the data modification should be associated with.
	 */
	public GoTermInstanceModifier(MySQLAdaptor adaptor, GKInstance instanceEdit)
	{
		this(adaptor,null,instanceEdit);
	}

	
	/**
	 * Creates a new GO Term in the database.
	 * @param goTerms - Map of GO terms, based on the file. Keyed by GO ID.
	 * @param goToEcNumbers - Mapping of GO-to-EC numbers. Keyed by GO ID.
	 * @param currentGOID - GO ID of the thing to insert.
	 * @param currentCategory - Current category/namespace. Will help choose which Reactome SchemaClass to use: GO_BiologicalProcess, GO_MolecularFunction, GO_CellularCompartment.
	 */
	public void createNewGOTerm(Map<String, Map<String, Object>> goTerms, Map<String,List<String>> goToEcNumbers, String currentGOID, String currentCategory, GKInstance goRefDB) throws Exception
	{
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
					newGOTerm.setAttributeValue(ReactomeJavaConstants.ecNumber, ecNumbers);
				}
			}
			InstanceDisplayNameGenerator.setDisplayName(newGOTerm);
			newGOTerm.setAttributeValue(ReactomeJavaConstants.created, this.instanceEdit);
			newGOTerm.setDbAdaptor(this.adaptor);
			this.adaptor.storeInstance(newGOTerm);
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			System.err.println("Attribute/value error! "+ e.getMessage());
			e.printStackTrace();
			throw e;
		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw e;
		}
	}
	
	/**
	 * Updates a GO instance that's already in the database. 
	 * @param goTerms - Mapping of GO terms from the file, keyed by GO ID.
	 * @param goToEcNumbers - Mapping of GO IDs mapped to EC numbers.
	 * @param currentDefinition - The category/namespace.
	 */
	public void updateGOInstance(Map<String, Map<String, Object>> goTerms, Map<String, List<String>> goToEcNumbers, StringBuffer nameOrDefinitionChangeStringBuilder)
	{
		String currentGOID = null;
		try
		{
			currentGOID = (String) this.goInstance.getAttributeValue(ReactomeJavaConstants.accession);
		}
		catch (InvalidAttributeException e1)
		{
			logger.error("InvalidAttributeException happened somehow, when querying \"{}\" on {}",ReactomeJavaConstants.accession,this.goInstance.toString());
			e1.printStackTrace();
		}
		catch (Exception e1)
		{
			e1.printStackTrace();
		}
		if (currentGOID!=null)
		{
			String newDefinition = (String) goTerms.get(currentGOID).get(GoUpdateConstants.DEF);
			String newName = (String) goTerms.get(currentGOID).get(GoUpdateConstants.NAME);
			String oldDefinition = null;
			String oldName = null;
			try
			{
				oldDefinition = (String) this.goInstance.getAttributeValue(ReactomeJavaConstants.definition);
				oldName = (String) this.goInstance.getAttributeValue(ReactomeJavaConstants.name);
				boolean modified = false;
				// according to the logic in the Perl code, if the existing name does not
				// match the name in the file or if the existing definition does not match
				// the one in the file, we update with the new name and def'n, and then set
				// InstanceOf and ComponentOf to NULL, and I guess those get updated later.
				if ((newName!=null && !newName.equals(oldName))
					|| (newDefinition != null && !newDefinition.equals(oldDefinition)))
				{
					nameOrDefinitionChangeStringBuilder.append("Change in name/definition for GO ID ").append(currentGOID).append("! ")
							.append("New name: \"").append(goTerms.get(currentGOID).get(GoUpdateConstants.NAME)).append("\" vs. old name: \"").append(this.goInstance.getAttributeValue(ReactomeJavaConstants.name)).append("\"")
							.append(" new def'n: \"").append(newDefinition).append("\" vs old def'n: \"").append(this.goInstance.getAttributeValue(ReactomeJavaConstants.definition)).append("\". ")
							.append("  instanceOf and componentOf fields will be cleared (and hopefully reset later in the process)\n");
					this.goInstance.setAttributeValue(ReactomeJavaConstants.instanceOf, null);
					this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.instanceOf);
					this.goInstance.setAttributeValue(ReactomeJavaConstants.componentOf, null);
					this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.componentOf);
					this.goInstance.setAttributeValue(ReactomeJavaConstants.name, goTerms.get(currentGOID).get(GoUpdateConstants.NAME));
					this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.name);
					this.goInstance.setAttributeValue(ReactomeJavaConstants.definition, newDefinition);
					this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.definition);
					modified = true;
				}
				
				if (this.goInstance.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction))
				{
					List<String> ecNumbers = goToEcNumbers.get(currentGOID);
					if (ecNumbers!=null)
					{
						// Clear out any old EC Numbers - only want to keep the freshest ones from the file.
						this.goInstance.setAttributeValue(ReactomeJavaConstants.ecNumber, null);
						this.goInstance.addAttributeValue(ReactomeJavaConstants.ecNumber, ecNumbers);
						//nameOrDefinitionChangeStringBuilder.append("GO Term (").append(currentGOID).append(") has new EC Number: ").append(ecNumbers.toString()).append("\n");
						modified = true;
						this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.ecNumber);
					}
				}
				if (modified)
				{
					this.goInstance.getAttributeValuesList(ReactomeJavaConstants.modified);
					this.goInstance.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
					InstanceDisplayNameGenerator.setDisplayName(this.goInstance);
					this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants._displayName);
					
					//Now... need to modify all referrers that refer to this, since their displayNames might need to be updated.
					//this.updateReferrersDisplayNames();
				}
			}
			catch (InvalidAttributeException | InvalidAttributeValueException e)
			{
				System.err.println("Attribute/Value problem with "+this.goInstance.toString()+ " " + e.getMessage());
				e.printStackTrace();
			}
			catch (NullPointerException e)
			{
				System.err.println("NullPointerException occurred! GO ID: "+currentGOID+" GO Instance: "+this.goInstance + " GO Term: "+goTerms.get(currentGOID));
				e.printStackTrace();
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Update the Instances that refer to the instance being modified by *this* GoTermInstanceModifier.
	 * @throws Exception 
	 */
	public void updateReferrersDisplayNames() throws Exception
	{
		@SuppressWarnings("unchecked")
		Set<GKSchemaAttribute> referringAttributes = (Set<GKSchemaAttribute>) this.goInstance.getSchemClass().getReferers();
		for(GKSchemaAttribute attribute : referringAttributes.stream().filter(a -> a.getName().equals(ReactomeJavaConstants.activity)
																		|| a.getName().equals(ReactomeJavaConstants.goCellularComponent))
																	.collect(Collectors.toList()))
		{
			@SuppressWarnings("unchecked")
			Collection<GKInstance> referrers = (Collection<GKInstance>) this.goInstance.getReferers(attribute.getName());
			if (referrers != null)
			{
				for (GKInstance referrer : referrers)
				{
					referrer.getAttributeValuesList(ReactomeJavaConstants.modified);
					referrer.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
					InstanceDisplayNameGenerator.setDisplayName(referrer);
					this.adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants._displayName);
					this.adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants.modified);
				}
			}
		}
	}

	/**
	 * Deletes a GO term from the database.
	 * @param goTerms - The list of GO terms from the file. Needed to get the alternate GO IDs for things that refer to the thing that's about to be deleted.
	 * @param allGoInstances - ALL GO instances from the database.
	 */
	public void deleteGoInstance(Map<String, Map<String,Object>> goTerms, Map<String, List<GKInstance>> allGoInstances, StringBuffer deletionStringBuilder)
	{
		try
		{
			String goId = (String) this.goInstance.getAttributeValue(ReactomeJavaConstants.accession);
			
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
			
			
			if (altGoId != null)
			{
				// The current instances GO ID is an alternate to others. So, we will re-direct referrers to that one.
				// If there's more than one, just use the first one.
				String replacementGoId = altGoId;
				if (allGoInstances.containsKey(replacementGoId))
				{
					GKInstance replacementGoInstance = allGoInstances.get(replacementGoId).get(0);
					Map<String, List<GKInstance>> referrers = new HashMap<String, List<GKInstance>>();
					for (String attribute : Arrays.asList(ReactomeJavaConstants.activity, "componentOf", "hasPart", "negativelyRegulate", "positivelyRegulate", "regulate"))
					{
						@SuppressWarnings("unchecked")
						List<GKInstance> tmp = (List<GKInstance>) this.goInstance.getReferers(attribute);
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
							// verify by DB ID
							if (tmp.getDBID() == this.goInstance.getDBID())
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
					logger.warn("Replacement GO Instance with GO ID: {} could not be found in allGoInstances map. This was not expected. Instance \"{}\" will still be deleted but referrs will have nothing to refer to.",replacementGoId, this.goInstance.toString() );
				}
			}
			adaptor.deleteInstance(this.goInstance);
			deletionStringBuilder.append("Deleting GO instance: \"").append(this.goInstance.toString()).append("\" (GO:").append(goId).append(")\n");
		}
		catch (Exception e)
		{
			System.err.println("Error occurred while trying to delete instance: \""+this.goInstance.toString()+"\": "+e.getMessage());
			e.printStackTrace();
		}
	}
	
	/**
	 * Updates the relationships between GO terms in the database.
	 * @param allGoInstances - Map of all GO instances in the database.
	 * @param goProps - The properties to update with.
	 * @param relationshipKey - The key to use to look up the values  in goProps.
	 * @param reactomeRelationshipName - The name of the relationship, can be one of "is_a", "has_part", "part_of", "component_of", "regulates", "positively_regulates", "negatively_regulates".
	 */
	public void updateRelationship(Map<String, List<GKInstance>> allGoInstances, Map<String, Object> goProps, String relationshipKey, String reactomeRelationshipName, StringBuffer updatedRelationshipStringBuilder)
	{
		if (goProps.containsKey(relationshipKey))
		{
			//List<GKInstance> newInstancesToAdd = new ArrayList<GKInstance>();
			@SuppressWarnings("unchecked")
			List<String> otherIDs = (List<String>) goProps.get(relationshipKey);
			try
			{
				// Clear the values that are currently set.
				this.goInstance.setAttributeValue(reactomeRelationshipName, null);
				this.adaptor.updateInstanceAttribute(this.goInstance, reactomeRelationshipName);

				for (String otherID : otherIDs)
				{				
					List<GKInstance> otherInsts = allGoInstances.get(otherID);
					// Before adding a new Instance, let's check that the relationship doesn't already exist.
					// First, we get the list of things currently under that attribute.
					if (otherInsts != null && !otherInsts.isEmpty())
					{
						// Add the new value from otherInsts
						this.goInstance.addAttributeValue(reactomeRelationshipName, otherInsts);
						this.adaptor.updateInstanceAttribute(this.goInstance, reactomeRelationshipName);
						updatedRelationshipStringBuilder.append("Relationship updated! \"").append(this.goInstance.toString()).append("\" (GO:").append(this.goInstance.getAttributeValue(ReactomeJavaConstants.accession))
							.append(") now has relationship \"").append(reactomeRelationshipName).append("\" referring to \"").append(otherInsts.stream().map(i -> {
								try
								{
									return i.toString();
								}
								catch (Exception e1)
								{
									e1.printStackTrace();
									return "";
								}
							} ).reduce("", (a,b) -> { return a + " " + b; })).append("\" ")
							.append(otherInsts.stream().map(i -> {
								try
								{
									return i.getAttributeValue(ReactomeJavaConstants.accession).toString();
								}
								catch (Exception e1)
								{
									e1.printStackTrace();
									return "";
								}
							} ).reduce("", (a,b) -> { return a+", GO:"+b; })).append(")\n");
					}
					else
					{
						String message = "Trying to set \""+reactomeRelationshipName+"\" on \""+this.goInstance.toString()+"\" but could not find instance with GO ID "+otherID+". Relationship update could not be completed.";
						updatedRelationshipStringBuilder.append(message + "\n");
						logger.warn(message);
					}
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}

}
