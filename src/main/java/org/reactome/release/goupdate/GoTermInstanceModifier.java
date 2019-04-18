package org.reactome.release.goupdate;

import java.util.Collection;
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

	private static final Logger logger = LogManager.getLogger();
	private static final Logger updatedGOTermLogger = LogManager.getLogger("updatedGOTermsLog");
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
	public Long createNewGOTerm(Map<String, Map<String, Object>> goTerms, Map<String,List<String>> goToEcNumbers, String currentGOID, String currentCategory, GKInstance goRefDB) throws Exception
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
			return this.adaptor.storeInstance(newGOTerm);
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			logger.error("Attribute/value error! "+ e.getMessage());
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
				// InstanceOf and ComponentOf to NULL, and those get updated later, from whatever's in the GO file.
				if ( (newName!=null && !newName.equals(oldName)) || (newDefinition != null && !newDefinition.equals(oldDefinition)))
				{
					// Changes for name
					if (newName!=null && !newName.equals(oldName))
					{
						String nameUpdate = newName.equals(oldName) ? "" : "\n\tNew name:\t\""+newName+"\"\n\told name:\t\""+this.goInstance.getAttributeValue(ReactomeJavaConstants.name)+"\"";
						nameOrDefinitionChangeStringBuilder.append("\nChange in name/definition for GO:").append(currentGOID).append(nameUpdate);
						this.goInstance.setAttributeValue(ReactomeJavaConstants.name, newName);
						this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.name);
					}
					// Changes for definition  
					if (newDefinition != null && !newDefinition.equals(oldDefinition))
					{
						String defnUpdate = newDefinition.equals(oldDefinition) ? "" : "\n\tNew def'n:\t\""+newDefinition+"\"\n\told def'n:\t\""+this.goInstance.getAttributeValue(ReactomeJavaConstants.definition)+"\"";
						nameOrDefinitionChangeStringBuilder.append("\nChange in name/definition for GO:").append(currentGOID).append(defnUpdate);
						this.goInstance.setAttributeValue(ReactomeJavaConstants.definition, newDefinition);
						this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.definition);
					}
					// Now, instanceOf and componentOf are *OLNLY* valid for GO_CellularComponent
					// instanceOf and componentOf get set to NULL and will be corrected later in the process.
					if (this.goInstance.getSchemClass().isa(ReactomeJavaConstants.GO_CellularComponent))
					{
						this.goInstance.setAttributeValue(ReactomeJavaConstants.instanceOf, null);
						this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.instanceOf);
						this.goInstance.setAttributeValue(ReactomeJavaConstants.componentOf, null);
						this.adaptor.updateInstanceAttribute(this.goInstance, ReactomeJavaConstants.componentOf);
					}
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
						//nameOrDefinitionChangeStringBuilder.append("GO Term (").append(currentGOID).append(") has new EC Number(s): ").append(ecNumbers.toString()).append("\n");
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
				}
			}
			catch (InvalidAttributeException | InvalidAttributeValueException e)
			{
				logger.error("Attribute/Value problem with "+this.goInstance.toString()+ " " + e.getMessage());
				e.printStackTrace();
			}
			catch (NullPointerException e)
			{
				logger.error("NullPointerException occurred! GO ID: "+currentGOID+" GO Instance: "+this.goInstance + " GO Term: "+goTerms.get(currentGOID));
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
		// The old Perl code only updated PhysicalEntities and CatalystActivities that referred to GO Terms. Events that referred
		// to GO terms via goBiologicalProcess were *not* updated in the old code. So I'm trying to keep this code consistent with
		// that implementation.
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
					InstanceDisplayNameGenerator.setDisplayName(referrer);
					referrer.getAttributeValuesList(ReactomeJavaConstants.modified);
					referrer.addAttributeValue(ReactomeJavaConstants.modified, this.instanceEdit);
					this.adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants._displayName);
					this.adaptor.updateInstanceAttribute(referrer, ReactomeJavaConstants.modified);
				}
			}
		}
	}

	/**
	 * Gets a collection of GKInstances the refer to a Go Term.
	 * @param instance - the instance to get referrers for.
	 * @return A collection:<br/>
	 * If the instance is a BiologicalProcess, 
	 * all instances that refer to it via goBiologicalProcess will be returned.<br/>
	 * If the instances is a CellularComponent then all instances that refer via compartment will be returned.<br/>
	 * If the instance is a MolecularFunction, all instances that refer via activity will be returned.<br/>
	 * NULL will be returned if there are no referrers.
	 * @throws Exception
	 */
	public static Collection<GKInstance> getReferrersForGoTerm(GKInstance instance) throws Exception
	{
		Collection<GKInstance> referrers = null;
		
		if (instance.getSchemClass().getName().equals(ReactomeJavaConstants.GO_BiologicalProcess))
		{
			referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.goBiologicalProcess);
			
		}
		else if (instance.getSchemClass().getName().equals(ReactomeJavaConstants.GO_CellularComponent))
		{
			referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.compartment);
			
		}
		else if (instance.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction))
		{
			referrers = (Collection<GKInstance>) instance.getReferers(ReactomeJavaConstants.activity);
		}
		
		return referrers;
	}
	
	/**
	 * If a GO Term has certain referrers, it is not deletable. The rules (from Peter D.) are:<br/><br/><br/>
	 * IF an GO biological process term has NOT been used as a goBiologicalProcess slot value for any event instance in gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
	 * IF a GO cellular component term has NOT been used as a compartment slot value for any physical entity or event instance in gk_central, the obsolete GO term instance can be deleted from gk_central.<br/><br/>
	 * IF a GO molecular function term has NOT been used as the activity slot value for any catalystActivity instance in gk_central, the obsolete GO term instance can be deleted from gk_central.
	 * @param instance - an instance to check.
	 * @return true or false, if <code>instance</code> is deleteable, as per the above rules.
	 * @throws Exception
	 */
	public static boolean isGoTermDeleteable(GKInstance instance) throws Exception
	{
		boolean isDeletable = false;
		
		Collection<GKInstance> referrers = GoTermInstanceModifier.getReferrersForGoTerm(instance);
		
		if (referrers != null && referrers.size() > 0)
		{
			isDeletable = false;
		}
		else
		{
			isDeletable = true;
		}
		
		return isDeletable;
	}
	
	
	public void deleteSecondaryGOInstance(GKInstance primaryGOTerm, StringBuffer deletionStringBuffer)
	{
		try
		{
			String goId = (String) this.goInstance.getAttributeValue(ReactomeJavaConstants.accession);
			pointAllReferrersToOtherInstance(primaryGOTerm);
			deletionStringBuffer.append("Deleting secondary GO instance: \"").append(this.goInstance.toString()).append("\" (GO:").append(goId).append(")\n");
			adaptor.deleteInstance(this.goInstance);
		}
		catch (Exception e)
		{
			logger.error("Error occurred while trying to delete instance: \""+this.goInstance.toString()+"\": "+e.getMessage());
			e.printStackTrace();
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
			// A GO term can be deleted if it has a replacement value
			if (goTerms.get(goId).get(GoUpdateConstants.REPLACED_BY)!= null)
			{
				// If there are multiple replacement options, just use the first one, no clear way to choose a replacement.
				@SuppressWarnings("unchecked")
				String replacementGOTermAccession = ((List<String>) goTerms.get(goId).get(GoUpdateConstants.REPLACED_BY)).get(0);
				// this term has a replacement so we will update all referrers of *this* to point to the replacement.
				if (allGoInstances.get(replacementGOTermAccession) != null && allGoInstances.get(replacementGOTermAccession).size() > 0)
				{
					GKInstance replacementGOTerm = allGoInstances.get(replacementGOTermAccession).get(0);
					this.pointAllReferrersToOtherInstance(replacementGOTerm);
				}
				deletionStringBuilder.append("Deleting GO instance: \"").append(this.goInstance.toString()).append("\" (GO:").append(goId).append(")\n");
				adaptor.deleteInstance(this.goInstance);
			}
			// A GO term that has no replacement value can still be deleted if it has no referrers.
			else if (GoTermsUpdater.getReferrerCounts(this.goInstance).isEmpty())
			{
				deletionStringBuilder.append("Deleting GO instance: \"").append(this.goInstance.toString()).append("\" (GO:").append(goId).append(")\n");
				adaptor.deleteInstance(this.goInstance);
			}
			else
			{
				logger.info("GO:{} ({}) is marked as obsolete but there is no replacement value specified! Instance will *NOT* be deleted, as manual clean-up may be necessary.", goId, this.goInstance.toString());
			}
		}
		catch (Exception e)
		{
			logger.error("Error occurred while trying to delete instance: \""+this.goInstance.toString()+"\": "+e.getMessage());
			e.printStackTrace();
		}
	}
	
	private void pointAllReferrersToOtherInstance(GKInstance replacementGOTerm) throws Exception
	{
		@SuppressWarnings("unchecked")
		Collection<GKSchemaAttribute> attributes = (Collection<GKSchemaAttribute>) this.goInstance.getSchemClass().getReferers();
		for (GKSchemaAttribute attribute : attributes)
		{
			String attributeName = attribute.getName();
			@SuppressWarnings("unchecked")
			Set<GKInstance> referrers = (Set<GKInstance>) this.goInstance.getReferers(attribute);
			if (referrers != null)
			{
				for (GKInstance referrer : referrers)
				{
					// the referrer could refer to many things via the attribute.
					// we should ONLY remove *this* GO instance that will probably be deleted
					// and add the replacement GO term. All other values should be left alone.
					@SuppressWarnings("unchecked")
					List<GKInstance> referrerAttributeValues = (List<GKInstance>) referrer.getAttributeValuesList(attribute);
					// remove *this* goInstance from the referrer
					referrerAttributeValues = referrerAttributeValues.parallelStream().filter(v -> !v.getDBID().equals(this.goInstance.getDBID())).collect(Collectors.toList());
					// add the replacement to the referrer
					referrerAttributeValues.add(replacementGOTerm);
					referrer.setAttributeValue(attributeName, referrerAttributeValues);
					// update in db.
					adaptor.updateInstanceAttribute(referrer, attributeName);
					logger.debug("\"{}\" now refers to \"{}\" via {}, instead of referring to \"{}\"", referrer.toString(), replacementGOTerm.toString(), attributeName, this.goInstance.toString());
				}
			}
		}
	}

	/**
	 * Updates the relationships between GO terms in the database.
	 * @param allGoInstances - Map of all GO instances in the database.
	 * @param goProps - The properties to update with.
	 * @param relationshipKey - The key to use to look up the values  in goProps.
	 * @param reactomeRelationshipName - The name of the relationship, can be one of "is_a", "has_part", "part_of", "component_of", "regulates", "positively_regulates", "negatively_regulates".
	 */
	public void updateRelationship(Map<String, List<GKInstance>> allGoInstances, Map<String, Object> goProps, String relationshipKey, String reactomeRelationshipName)
	{
		if (goProps.containsKey(relationshipKey))
		{
			@SuppressWarnings("unchecked")
			List<String> otherIDs = (List<String>) goProps.get(relationshipKey);
			try
			{
				// Clear the values that are currently set.
				this.goInstance.setAttributeValue(reactomeRelationshipName, null);
				this.adaptor.updateInstanceAttribute(this.goInstance, reactomeRelationshipName);

				for (String otherID : otherIDs)
				{
					// This is tricky - allGoInstances could contain duplicated GO accessions, because the database could contains multiple GO terms with the same GO accession.
					List<GKInstance> otherInsts = allGoInstances.get(otherID);
					if (otherInsts != null && !otherInsts.isEmpty())
					{
						// Only use the first item, so we don't end up attaching multiple GO Terms with the same accession to this object via "reactomeRelationshipName".
						// I think this is what the Perl code does when it encounters duplicates. Not ideal, but seems to work OK.
						if (otherInsts.size() > 1)
						{
							otherInsts = otherInsts.subList(0, 1);
						}
						// Add the new value from otherInsts
						this.goInstance.addAttributeValue(reactomeRelationshipName, otherInsts);
						this.adaptor.updateInstanceAttribute(this.goInstance, reactomeRelationshipName);
						updatedGOTermLogger.info("GO:{} ({}) now has relationship \"{}\" referring to {}", this.goInstance.getAttributeValue(ReactomeJavaConstants.accession), this.goInstance.toString(), reactomeRelationshipName, 
								otherInsts.stream().map(i -> {
									try
									{
										return "GO:"+i.getAttributeValue(ReactomeJavaConstants.accession).toString()+" (" + i.toString() + ")";
									}
									catch (Exception e1)
									{
										e1.printStackTrace();
										return "";
									}
								} ).reduce("", (a,b) -> { return a + ", " + b; }));
					}
					else
					{
						updatedGOTermLogger.warn("Trying to set {} on GO:{} ({}) but could not find instance with GO ID = {}. Relationship update could not be completed.", reactomeRelationshipName, this.goInstance.getAttributeValue(ReactomeJavaConstants.accession), this.goInstance.toString(), otherID);
					}
				}
			}
			catch (InvalidAttributeValueException e)
			{
				logger.error(e.getMessage());
				logger.error("Tried to set the '{}' attribute of \"{}\", but this attribute is not valid for this object.", reactomeRelationshipName, this.goInstance.toString());
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}

}
