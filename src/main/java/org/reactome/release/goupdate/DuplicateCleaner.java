package org.reactome.release.goupdate;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.InvalidAttributeException;
import org.reactome.release.common.ReleaseStep;

/**
 * Stand-alone class with main method that can be used to clean up duplicate GO accessions.
 * @author sshorser
 *
 */
public class DuplicateCleaner extends ReleaseStep
{
	private static final Logger logger = LogManager.getLogger();
	private MySQLAdaptor adaptor;
	private static final String[] goClasses = {ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.Compartment};

	public static void main(String[] args)
	{
		String pathToResources = "src/main/resources/go-update.properties";
		if (args.length > 0)
		{
			pathToResources = args[0];
		}
		try
		{
			Properties props = new Properties();
			props.load(new FileInputStream(pathToResources));
			DuplicateCleaner step = new DuplicateCleaner();
			step.executeStep(props);
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

	}

	@Override
	public void executeStep(Properties props) throws Exception
	{
		this.adaptor = DuplicateCleaner.getMySQLAdaptorFromProperties(props);
		int totalDuplicatesCount = 0;
		int instancesWithSignificantReferrers = 0;
		
		DuplicateReporter dupeReporter = new DuplicateReporter(adaptor);
		Set<Long> dbIDsToDelete = new HashSet<Long>();

		Map<String, Integer> duplicates = dupeReporter.getDuplicateAccessions();
		
		// loop through all duplicated accessions.
		for (String accession : duplicates.keySet())
		{
			totalDuplicatesCount = duplicates.keySet().size();
			logger.info("Accession {} is duplicated {} times.", accession, duplicates.get(accession));
			List<Long> dbIDsWithNoReferrers = new ArrayList<Long>();
			Map <Long,Integer> refCounts = dupeReporter.getReferrerCountForAccession(accession, goClasses);
			// if there are referrers...
			if (refCounts != null && refCounts.size() > 0)
			{
				// for each DB ID in referrer counts
				for (Long dbId : refCounts.keySet())
				{
					// if there are referrers, we need to report them.
					if (refCounts.get(dbId) > 0)
					{
						// update the counter of all instances with significant referrers.
						instancesWithSignificantReferrers ++;
						// log info about the referrers 
						logReferrers(dbId, refCounts);
					}
					// if there are NO referrers, add the db_id to the list of DB IDs without referrers.
					else
					{
						logger.debug("DB ID {} for accession {} has no significant referrers, and will be deleted.", dbId, accession);
						dbIDsWithNoReferrers.add(dbId);
					}
				}
			}
			// update list of things to delete with IDs that have no referrers.
			dbIDsToDelete.addAll(dbIDsWithNoReferrers);
			// If no DB IDs have significant referrers, we should delete ALL DB_IDs for the accession, except for the newest one.
			if (dbIDsWithNoReferrers.size() == refCounts.keySet().size())
			{
				GKInstance newestInstance = null;
				// Need to try all classes since we don't know which class a GO Accession might be. Note: this.goClasses also contains "Compartment", but here "Compartment" is not used.
				// It might be safe to use this.goClasses here, but since this bit of code did not use Compartment, we are not going to use this.goClasses here, for now.
				for (String reactomeClass : Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent))
				{
					@SuppressWarnings("unchecked")
					Collection<GKInstance> instances = (Collection<GKInstance>) adaptor.fetchInstanceByAttribute(reactomeClass, ReactomeJavaConstants.accession, "=", accession);
					// For each instance for this accession, try to find the newest one so that it won't be deleted.
					if (instances != null && instances.size() > 0)
					{
						// Add all the DB_IDs to the list to delete. Later, only the DB_ID of the NEWEST instance will be removed from this list, and all other older instances will be deleted.
						dbIDsToDelete.addAll(instances.stream().map(i -> i.getDBID()).collect(Collectors.toSet()));
						newestInstance = findNewestInstance(instances);
					}
				}
				logger.debug("Newest instance for accession {} is {} - this instance will NOT be deleted.", accession, newestInstance.toString());
				// REMOVE the ID of the newest instance from the list of things to delete (it would have been automatically added if the "newest" instance
				// had 0 referrers).
				dbIDsToDelete.remove(newestInstance.getDBID());
			}
		}
		logger.info("\n\nSummary:\nTotal number of duplicated accessions: {} \n"
				+ "Number of instances with significant (non-GO Term) referrers: {}\n", totalDuplicatesCount, instancesWithSignificantReferrers);
		logger.info("{} IDs will be deleted.", dbIDsToDelete.size());
		adaptor.startTransaction();
		for (Long dbID : dbIDsToDelete)
		{
			GKInstance instance = adaptor.fetchInstance(dbID);
			logger.info("DB ID {} (for accession {}) will be deleted", dbID, instance.getAttributeValue(ReactomeJavaConstants.accession));
			if (!this.testMode)
			{
				adaptor.deleteByDBID(dbID);
			}
		}
		adaptor.commit();
	}

	/**
	 * Finds the newest GKInstance in a list of instances.
	 * @param instances - The list of instances
	 * @return The newest instance.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 */
	private GKInstance findNewestInstance(Collection<GKInstance> instances) throws InvalidAttributeException, Exception
	{
		GKInstance newestInstance = null;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
		LocalDateTime newestDate = null;
		for (GKInstance instance : instances)
		{
			LocalDateTime localDate = null;
			GKInstance modifiedInstance = null;
			modifiedInstance = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.modified);
			
			if (modifiedInstance != null)
			{
				localDate = LocalDateTime.parse((CharSequence) modifiedInstance.getAttributeValue(ReactomeJavaConstants.dateTime), formatter);
			}
			else
			{
				modifiedInstance = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.created);
				localDate = LocalDateTime.parse((CharSequence) modifiedInstance.getAttributeValue(ReactomeJavaConstants.dateTime), formatter);
			}

			if (newestInstance == null)
			{
				newestInstance = instance;
				newestDate = localDate;
				
			}
			if (localDate != null && newestDate != null && localDate.compareTo(newestDate) > 0)
			{
				newestDate = localDate;
				newestInstance = instance;
			}
		}
		return newestInstance;
	}

	/**
	 * Logs referrers.
	 * @param refCounts 
	 * @param refAtt
	 * @param refs
	 * @throws Exception 
	 */
	private void logReferrers(Long dbId, Map<Long, Integer> refCounts) throws Exception
	{
		GKInstance inst = adaptor.fetchInstance(dbId);
		logger.info("\tAccession instance with DB_ID {} has {} significant referrers", inst.getDBID(), refCounts.get(dbId));
		// Get the referring attributes for this instance's class.
		@SuppressWarnings("unchecked")
		Collection<GKSchemaAttribute> refAttribs = (Collection<GKSchemaAttribute>) inst.getSchemClass().getReferers();
		// Check each referring attribute. If there are referrers and they are not a GO_* class, then it must be reported.
		for (GKSchemaAttribute refAtt : refAttribs)
		{
			// Get the referrers for the current referrer attribute.
			@SuppressWarnings("unchecked")
			Collection<GKInstance> refs = (Collection<GKInstance>) inst.getReferers(refAtt);
			if (refs != null && refs.size() > 0)
			{
				for (GKInstance ref : refs)
				{
					// Report the referrer instance, if it is not a: GO_MolecularFunction, GO_BiologicalProcess, GO_CellularComponent, or Compartment.
					if (!(Arrays.asList(goClasses)).contains(ref.getSchemClass().getName()))
					{
						logger.info("\t\tvia {}:\t{}", refAtt.getName(), ref.toString());
					}
				}
			}
		}
	}
}
