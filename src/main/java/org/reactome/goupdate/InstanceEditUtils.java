package org.reactome.goupdate;

import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.PersistenceAdaptor;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.gk.schema.SchemaClass;
import org.gk.util.GKApplicationUtilities;

public class InstanceEditUtils
{
	/**
	 * Create an InstanceEdit.
	 * 
	 * @param personID
	 *            - ID of the associated Person entity.
	 * @param creatorName
	 *            - The name of the thing that is creating this InstanceEdit.
	 *            Typically, you would want to use the package and classname that
	 *            uses <i>this</i> object, so it can be traced to the appropriate
	 *            part of the program.
	 * @return
	 */
	public static GKInstance createInstanceEdit(MySQLAdaptor adaptor, long personID, String creatorName)
	{
		GKInstance instanceEdit = null;
		try
		{
			instanceEdit = createDefaultIE(adaptor, personID, true, "Inserted by " + creatorName);
			instanceEdit.getDBID();
			adaptor.updateInstance(instanceEdit);
		}
		catch (Exception e)
		{
			// logger.error("Exception caught while trying to create an InstanceEdit: {}",
			// e.getMessage());
			e.printStackTrace();
		}
		return instanceEdit;
	}

	// This code below was taken from 'add-links' repo:
	// org.reactomeaddlinks.db.ReferenceCreator
	/**
	 * Create and save in the database a default InstanceEdit associated with the
	 * Person entity whose DB_ID is <i>defaultPersonId</i>.
	 * 
	 * @param dba
	 * @param defaultPersonId
	 * @param needStore
	 * @return an InstanceEdit object.
	 * @throws Exception
	 */
	public static GKInstance createDefaultIE(MySQLAdaptor dba, Long defaultPersonId, boolean needStore, String note) throws Exception
	{
		GKInstance defaultPerson = dba.fetchInstance(defaultPersonId);
		if (defaultPerson != null)
		{
			GKInstance newIE = createDefaultInstanceEdit(defaultPerson);
			newIE.addAttributeValue(ReactomeJavaConstants.dateTime, GKApplicationUtilities.getDateTime());
			newIE.addAttributeValue(ReactomeJavaConstants.note, note);
			InstanceDisplayNameGenerator.setDisplayName(newIE);

			if (needStore)
			{
				dba.storeInstance(newIE);
			}
//			else
//			{
//				logger.info("needStore set to false");
//			}
			return newIE;
		}
		else
		{
			throw new Exception("Could not fetch Person entity with ID " + defaultPersonId + ". Please check that a Person entity exists in the database with this ID.");
		}
	}

	public static GKInstance createDefaultInstanceEdit(GKInstance person)
	{
		GKInstance instanceEdit = new GKInstance();
		PersistenceAdaptor adaptor = person.getDbAdaptor();
		instanceEdit.setDbAdaptor(adaptor);
		SchemaClass cls = adaptor.getSchema().getClassByName(ReactomeJavaConstants.InstanceEdit);
		instanceEdit.setSchemaClass(cls);

		try
		{
			instanceEdit.addAttributeValue(ReactomeJavaConstants.author, person);
		}
		catch (InvalidAttributeException | InvalidAttributeValueException e)
		{
			e.printStackTrace();
			// throw this back up the stack - no way to recover from in here.
			throw new Error(e);
		}

		return instanceEdit;
	}
}
