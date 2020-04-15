package org.reactome.release.goupdate;

import java.util.EnumMap;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.release.common.database.InstanceEditUtils;

class GoUpdateInstanceEditUtils
{
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
	
	// For each type of InstanceEdit update we have, there is a map of actual GKInstances. The reason is that the instances could vary
	// by having a different Java class name in their note. The class names are to help track down *where* in the code the InstanceEdit was used. 
	private static Map<GOUpdateInstEditType, Map<Class<?>, GKInstance>> availableInstanceEdits = new EnumMap<>(GOUpdateInstEditType.class);

	private static MySQLAdaptor adaptor;
	
	private static long personID;

	public static void setAdaptor(MySQLAdaptor adaptor)
	{
		GoUpdateInstanceEditUtils.adaptor = adaptor;
	}

	public static void setPersonID(long personID)
	{
		GoUpdateInstanceEditUtils.personID = personID;
	}

	public static GKInstance getInstanceEditForClass(GOUpdateInstEditType instanceEditType, Class<?> classUsingInstanceEdit) throws Exception
	{
		GKInstance instanceEdit = null;
		if (GoUpdateInstanceEditUtils.availableInstanceEdits.containsKey(instanceEditType))
		{
			instanceEdit = availableInstanceEdits.get(instanceEditType).get(classUsingInstanceEdit);
			// If there is no InstanceEdit for the class in question, need to create one.
			if (instanceEdit == null)
			{
				instanceEdit = InstanceEditUtils.createDefaultIE(adaptor, personID, true, instanceEditType.getNote() + "\nCreated by: " + classUsingInstanceEdit.getName());
				availableInstanceEdits.get(instanceEditType).put(classUsingInstanceEdit, instanceEdit);
			}
		}
		return instanceEdit;
	}
}

