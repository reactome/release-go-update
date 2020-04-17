package org.reactome.release.goupdate;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.release.common.database.InstanceEditUtils;

/**
 * Some utility methods for working with InstanceEdits for GO Update.
 * @author sshorser
 *
 */
class GoUpdateInstanceEditUtils
{
	/**
	 * Different types of instance edits.
	 * @author sshorser
	 *
	 */
	enum GOUpdateInstEditType
	{
		NEW("New GO term was created"),
		MODIFIED("GO term attributes were modified"),
		REF_CLEARED("Attribute referring to a GO term has been cleared"),
		REF_ATTRIB_UPDATE("Attribute referring to a GO term has been set to a *different* GO term"),
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
	
	// For each type of InstanceEdit update we have, there is a map of actual GKInstances. The reason is that the instances could vary
	// by having a different Java class name in their note. The class names are to help track down *where* in the code the InstanceEdit was used. 
	private static Map<GOUpdateInstEditType, Map<Class<?>, GKInstance>> availableInstanceEdits = new EnumMap<>(GOUpdateInstEditType.class);

	private static MySQLAdaptor adaptor;
	
	private static long personID;

	/**
	 * Sets the adaptor used by the utility methods.
	 * @param adaptor
	 */
	public static void setAdaptor(MySQLAdaptor adaptor)
	{
		GoUpdateInstanceEditUtils.adaptor = adaptor;
	}

	/**
	 * Sets the Person ID that will be used when creating new InstanceEdits.
	 * @param personID
	 */
	public static void setPersonID(long personID)
	{
		GoUpdateInstanceEditUtils.personID = personID;
	}

	/**
	 * Gets an InstanceEdit object for a given type and class. If no such InstanceEdit exists, a new one will be created!<br/>
	 * The name of the class will be appended to the InstanceEdit's "note" attribute as a new line, beggingin with "Created by:"<br/>
	 * The InstanceEdit will be created using the adaptor and person ID that have been set using the appropriate setters.
	 * @param instanceEditType - The InstanceEdit type.
	 * @param classUsingInstanceEdit - The Class that is using this InstanceEdit.
	 * @return A GKInstance that is an InstanceEdit.
	 * @throws Exception
	 */
	public static GKInstance getInstanceEditForClass(GOUpdateInstEditType instanceEditType, Class<?> classUsingInstanceEdit) throws Exception
	{
		GKInstance instanceEdit = null;
		if (GoUpdateInstanceEditUtils.availableInstanceEdits.containsKey(instanceEditType))
		{
			instanceEdit = availableInstanceEdits.get(instanceEditType).get(classUsingInstanceEdit);
		}
		// If there is no InstanceEdit for the class in question, we need to create one.
		if (instanceEdit == null)
		{
			instanceEdit = InstanceEditUtils.createDefaultIE(adaptor, personID, true, instanceEditType.getNote() + "\nCreated by: " + classUsingInstanceEdit.getName());
			Map<Class<?>, GKInstance> existingInstEds = availableInstanceEdits.computeIfAbsent(instanceEditType, x -> new HashMap<>());
			existingInstEds.put(classUsingInstanceEdit, instanceEdit);
			availableInstanceEdits.put(instanceEditType, existingInstEds);
		}
		// If there is STILL no InstanceEdit, something's gone wrong, abort execution.
		if (instanceEdit == null)
		{
			throw new RuntimeException("Unable to get an InstanceEdit; this program cannot continue without an InstanceEdit. Terminating execution.");
		}
		return instanceEdit;
	}
}

