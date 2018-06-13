package org.reactome.goupdate;

import java.util.regex.Pattern;

public final class GoUpdateConstants {
	static final String ID = "id";
	static final String ALT_ID = "alt_id";
	static final String NAME = "name";
	static final String NAMESPACE = "namespace";
	static final String DEF = "def";
	//private static final String SUBSET = "subset";
	static final String RELATIONSHIP = "relationship";
	static final String IS_A = "is_a";
	static final String CONSIDER = "consider";
	static final String REPLACED_BY = "replaced_by";
	static final String SYNONYM = "synonym";
	static final String HAS_PART = "has_part";
	static final String PART_OF = "part_of";
	static final String REGULATES = "regulates";
	static final String POSITIVELY_REGULATES = "positively_"+REGULATES;
	static final String NEGATIVELY_REGULATES = "negatively_"+REGULATES;
	static final String IS_OBSOLETE = "is_obsolete";
	static final String PENDING_OBSOLETION = "pending_obsoletion";
	
	static final Pattern LINE_DECODER = Pattern.compile("^(id|alt_id|name|namespace|def|subset|relationship|is_a|consider|replaced_by|synonym|is_obsolete):.*");
	static final Pattern RELATIONSHIP_DECODER = Pattern.compile("^relationship: (positively_regulates|negatively_regulates|has_part|part_of|regulates) GO:[0-9]+");
	static final Pattern OBSOLETION = Pattern.compile("(pending|scheduled for|slated for) obsoletion");
	static final Pattern IS_OBSOLETE_REGEX = Pattern.compile("^"+IS_OBSOLETE+": true");
	static final Pattern ALT_ID_REGEX = Pattern.compile("^"+ALT_ID+": GO:([0-9]+)");
	static final Pattern NAMESPACE_REGEX = Pattern.compile("^"+NAMESPACE+": ([a-zA-Z_]*)");
	static final Pattern GO_ID_REGEX = Pattern.compile("^"+ID+": GO:([0-9]+)");
	static final Pattern NAME_REGEX = Pattern.compile("^"+NAME+": (.*)");
	static final Pattern DEF_REGEX = Pattern.compile("^"+DEF+": (.*)");
	static final Pattern IS_A_REGEX = Pattern.compile("^"+IS_A+": GO:([0-9]+) .*");
	static final Pattern SYNONYM_REGEX = Pattern.compile("^"+SYNONYM+": (.*)");
	static final Pattern CONSIDER_REGEX = Pattern.compile("^"+CONSIDER+": GO:([0-9]+) .*");
	static final Pattern REPLACED_BY_REGEX = Pattern.compile("^"+REPLACED_BY+": GO:([0-9]+) .*");
	static final Pattern RELATIONSHIP_PART_OF_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+PART_OF+" GO:([0-9]+) .*");
	static final Pattern RELATIONSHIP_HAS_PART_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+HAS_PART+" GO:([0-9]+) .*");
	static final Pattern RELATIONSHIP_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+REGULATES+" GO:([0-9]+) .*");
	static final Pattern RELATIONSHIP_POSITIVELY_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+POSITIVELY_REGULATES+" GO:([0-9]+) .*");
	static final Pattern RELATIONSHIP_NEGATIVELY_REGULATES_REGEX = Pattern.compile("^"+RELATIONSHIP+": "+NEGATIVELY_REGULATES+" GO:([0-9]+) .*");

	// prevent instantiation.
	private GoUpdateConstants () {}
	
}
