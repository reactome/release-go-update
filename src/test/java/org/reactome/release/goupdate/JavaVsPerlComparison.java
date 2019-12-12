package org.reactome.release.goupdate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.reactome.util.compare.DBObjectComparer;

public class JavaVsPerlComparison
{
	public static void main(String[] args) throws Exception
	{
		MySQLAdaptor perlUpdatedDB = new MySQLAdaptor("localhost", "perl_gk_central", "root", "root", 3306);
		MySQLAdaptor javaUpdatedDB = new MySQLAdaptor("localhost", "java_gk_central", "root", "root", 3306);

		Map<String, Integer> diffNameToCount = new ConcurrentHashMap<>();
		Map<String, Integer> sameNameToCount = new ConcurrentHashMap<>();

		AtomicInteger diffCount = new AtomicInteger(0);
		AtomicInteger sameCount = new AtomicInteger(0);

		AtomicInteger regulationDiffCount = new AtomicInteger(0);
		AtomicInteger regulationSameCount = new AtomicInteger(0);

		AtomicInteger peDiffCount = new AtomicInteger(0);
		AtomicInteger peSameCount = new AtomicInteger(0);

		AtomicInteger catActDiffCount = new AtomicInteger(0);
		AtomicInteger catActSameCount = new AtomicInteger(0);

		AtomicInteger accessionsMissingFromPerl = new AtomicInteger(0);

		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOBiologicalProcesses =
			(Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOMolecularFunctions =
			(Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOCellularComponents =
			(Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);

		System.out.println("All Java GO instances obtained");

		List<GKInstance> listOfAllJavaGOThings = new ArrayList<>();
		listOfAllJavaGOThings.addAll(javaUpdatedGOCellularComponents);
		listOfAllJavaGOThings.addAll(javaUpdatedGOMolecularFunctions);
		listOfAllJavaGOThings.addAll(javaUpdatedGOBiologicalProcesses);

		@SuppressWarnings("unchecked")
		Set<GKInstance> perlUpdatedGOBiologicalProcesses =
			(Set<GKInstance>) perlUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
		@SuppressWarnings("unchecked")
		Set<GKInstance> perlUpdatedGOMolecularFunctions = (
			Set<GKInstance>) perlUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
		@SuppressWarnings("unchecked")
		Set<GKInstance> perlUpdatedGOCellularComponents =
			(Set<GKInstance>) perlUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);

		System.out.println("All Perl GO instances obtained");

		List<GKInstance> listOfAllPerlGOThings = new ArrayList<>();
		listOfAllPerlGOThings.addAll(perlUpdatedGOCellularComponents);
		listOfAllPerlGOThings.addAll(perlUpdatedGOMolecularFunctions);
		listOfAllPerlGOThings.addAll(perlUpdatedGOBiologicalProcesses);

		Map<String, List<GKInstance>> accessionToPerlInstances = new HashMap<>();
		for (GKInstance perlGOInstance : listOfAllPerlGOThings) {
			accessionToPerlInstances.computeIfAbsent(
				getAccession(perlGOInstance), k -> new ArrayList<>()
			).add(perlGOInstance);
		}


		Comparator<? super GKInstance> dbIdComparator = new Comparator<GKInstance>()
		{
			@Override
			public int compare(GKInstance o1, GKInstance o2)
			{
				return o1.getDBID().compareTo(o2.getDBID());
			}

		};

		listOfAllJavaGOThings.sort(dbIdComparator);
		StringBuilder mainSB = new StringBuilder();
		Map<String, MySQLAdaptor> javaAdaptorPool = new HashMap<>();
		Map<String, MySQLAdaptor> perlAdaptorPool = new HashMap<>();

		System.out.println("Number of Java GO instances is " + listOfAllJavaGOThings.size());

		AtomicInteger processJavaGOInstances = new AtomicInteger(0);
		listOfAllJavaGOThings.parallelStream().forEach( javaGoInst ->
		{
			try
			{
				String threadID = Long.toString(Thread.currentThread().getId());
				MySQLAdaptor javaDBAdaptor ;
				if (!javaAdaptorPool.containsKey(threadID))
				{
					javaDBAdaptor = new MySQLAdaptor(
						javaUpdatedDB.getDBHost(), javaUpdatedDB.getDBName(), javaUpdatedDB.getDBUser(),
						javaUpdatedDB.getDBPwd(), javaUpdatedDB.getDBPort()
					);
					javaAdaptorPool.put(threadID, javaDBAdaptor);
				}
				else
				{
					javaDBAdaptor = javaAdaptorPool.get(threadID);
				}
				MySQLAdaptor perlDBAdaptor ;
				if (!perlAdaptorPool.containsKey(threadID))
				{
					perlDBAdaptor = new MySQLAdaptor(
						perlUpdatedDB.getDBHost(), perlUpdatedDB.getDBName(), perlUpdatedDB.getDBUser(),
						perlUpdatedDB.getDBPwd(), perlUpdatedDB.getDBPort()
					);
					perlAdaptorPool.put(threadID, perlDBAdaptor);
				}
				else
				{
					perlDBAdaptor = perlAdaptorPool.get(threadID);
				}

				javaGoInst.setDbAdaptor(javaDBAdaptor);
				String accession = (String) javaGoInst.getAttributeValue(ReactomeJavaConstants.accession);
				@SuppressWarnings("unchecked")
				Collection<GKInstance> perlGoInsts = accessionToPerlInstances.get(accession);

				if (perlGoInsts == null || perlGoInsts.size() == 0)
				{
					mainSB.append("Perl database does not have accession " + accession + "\n");
					accessionsMissingFromPerl.getAndIncrement();
				}
				else
				{
					for (GKInstance perlInst : perlGoInsts)
					{
						StringBuilder sb = new StringBuilder();
						int i = DBObjectComparer.compareInstances(javaGoInst, perlInst, sb, 0, true);
						if (i> 0)
						{
							diffNameToCount.put(perlInst.getExtendedDisplayName(), i);
							diffCount.incrementAndGet();
							mainSB.append(
								"\n***\n" +
									"For Java-updated instance '" + javaGoInst + "' with accession '" +
									accession + "' " + "there are " + i + " differences:\n" + sb.toString());
						}
						else
						{
							sameNameToCount.put(perlInst.getExtendedDisplayName(), i);
							sameCount.incrementAndGet();
						}
						Predicate<? super SchemaAttribute> isNameAttribute = p-> {
							return p.getName().equals("name") || p.getName().equals("_displayName");
						};

						// Now, we need to compare referrers, since they might have display name changes.
						// Want to make sure we capture those correctly.
						if (javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_BiologicalProcess))
						{
							StringBuilder sb1 = new StringBuilder();

							@SuppressWarnings("unchecked")
							List<GKInstance> javaList =
								((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.goBiologicalProcess));
							@SuppressWarnings("unchecked")
							List<GKInstance> perlList =
								((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.goBiologicalProcess));
							if (javaList != null && perlList != null)
							{
								javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
								perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());

								GKInstance javaReferrer =  javaList.get(0);
								GKInstance perlReferrer = perlList.get(0);

								int numDiffs = DBObjectComparer.compareInstances(
									javaReferrer, perlReferrer, sb1, 0, isNameAttribute, true
								);
								if (numDiffs > 0)
								{
									regulationDiffCount.incrementAndGet();
									mainSB.append(
										"\n***\n" +
											"For Java-updated referring instance '" + javaReferrer + "' there are " +
											numDiffs + " differences:\n" + sb1.toString());
								}
								else
								{
									regulationSameCount.incrementAndGet();
								}
							}
						}
						else if (
							javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_CellularComponent)
						)
						{
							StringBuilder sb1 = new StringBuilder();
							@SuppressWarnings("unchecked")
							List<GKInstance> javaList =
								((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.goCellularComponent));
							@SuppressWarnings("unchecked")
							List<GKInstance> perlList =
								((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.goCellularComponent));

							if (javaList!=null && perlList!=null)
							{
								javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
								perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());
								GKInstance javaReferringPE = javaList.get(0);
								GKInstance perlReferringPE = perlList.get(0);

								int numDiffs = DBObjectComparer.compareInstances(
									javaReferringPE, perlReferringPE, sb1, 0, isNameAttribute, true
								);
								if (numDiffs > 0)
								{
									peDiffCount.incrementAndGet();
									mainSB.append(
										"\n***\n" +
											"For Java-updated referring PhysicalEntity instance '" + javaReferringPE +
											"' there are " + numDiffs + " differences:\n" + sb1.toString());
								}
								else
								{
									peSameCount.incrementAndGet();
								}
							}
						}
						else if (
							javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction)
						)
						{
							StringBuilder sb1 = new StringBuilder();
							@SuppressWarnings("unchecked")
							List<GKInstance> javaList =
								((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.activity));
							@SuppressWarnings("unchecked")
							List<GKInstance> perlList =
								((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.activity));

							if (javaList!=null && perlList!=null)
							{
								javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
								perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());

								GKInstance javaReferringCatalystActivity =  javaList.get(0);
								GKInstance perlReferringCatalystActivity =  perlList.get(0);

								int numDiffs = DBObjectComparer.compareInstances(
									javaReferringCatalystActivity, perlReferringCatalystActivity, sb1, 0,
									isNameAttribute, true
								);
								if (numDiffs > 0)
								{
									catActDiffCount.incrementAndGet();
									mainSB.append(
										"\n***\n" +
											"For Java-updated referring CatalystActivity instance '" +
											javaReferringCatalystActivity + "' there are " + numDiffs +
											" differences:\n" + sb1.toString());
								}
								else
								{
									catActSameCount.incrementAndGet();
								}
							}
						}
					}
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
				throw new RuntimeException(e);
			}

			if (processJavaGOInstances.get() % 100 == 0) {
				System.out.print(
					"Processed " + processJavaGOInstances.get() +
						" Java GO instances out of " + listOfAllJavaGOThings.size() + System.lineSeparator() +
						" " + (processJavaGOInstances.get() * 100.0f / listOfAllJavaGOThings.size()) + " %"
				);

			}
			processJavaGOInstances.incrementAndGet();
		});

		String prefix = "comparison";
		Files.write(Paths.get( prefix + ".txt"), mainSB.toString().getBytes(), StandardOpenOption.CREATE);
		Files.write(
			Paths.get(prefix + "_same_instances.txt"),
			getNameValuesAsString(sameNameToCount.keySet()).getBytes(),
			StandardOpenOption.CREATE
		);
		Files.write(
			Paths.get(prefix + "different_instances.txt"),
			getEntryValuesAsString(diffNameToCount.entrySet()).getBytes(),
			StandardOpenOption.CREATE
		);

		System.out.println(accessionsMissingFromPerl.get() + " accessions are missing from the Perl database.");
		System.out.println(sameCount.get()+" instances were the same");
		System.out.println(diffCount.get()+" instances were different");
		System.out.println("\nFor instances that *refer* to GO instances...\n");
		System.out.println(peDiffCount.get() + " PhysicalEntities had differences.");
		System.out.println(peSameCount.get() + " PhysicalEntities were the same.\n");
		System.out.println(regulationDiffCount.get() + " Regulations had differences.");
		System.out.println(regulationSameCount.get() + " Regulations were the same.\n");
		System.out.println(catActDiffCount.get() + " CatalystActivities had differences.");
		System.out.println(catActSameCount.get() + " CatalystActivities were the same.\n");
	}

	private static String getAccession(GKInstance instance)
	{
		try {
			return (String) instance.getAttributeValue(ReactomeJavaConstants.accession);
		} catch (Exception e) {
			throw new RuntimeException("Can't find accession for instance " + instance.getDBID(), e);
		}
	}

	private static String getNameValuesAsString(Set<String> values)
	{
		return values
			.stream()
			.map(l -> l.concat(System.lineSeparator()))
			.collect(Collectors.toSet())
			.toString();
	}

	private static String getEntryValuesAsString(Set<Entry<String, Integer>> values)
	{
		return values
			.stream()
			.map(e -> e.toString().concat(System.lineSeparator()))
			.collect(Collectors.toSet())
			.toString();
	}
}
