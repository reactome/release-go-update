package org.reactome.release.goupdate.model.parser;

import org.reactome.release.goupdate.GONamespace;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GoTermParser {
    private List<String> fileLines;
    private Map<String, List<String>> goAccession2ECNumbers;

    public GoTermParser(List<String> fileLines, Map<String, List<String>> goAccession2ECNumbers) {
        this.fileLines = fileLines;
        this.goAccession2ECNumbers = goAccession2ECNumbers;
    }

    public Iterator<? extends GoTerm> getGoTermIterator() throws IOException {
        return new Iterator<>() {
            final Iterator<String> fileLineIterator = getGoTermsFileLines().iterator();
            GoTerm nextGoTerm = readNextGoTerm();

            @Override
            public boolean hasNext() {
                return this.nextGoTerm != null;
            }

            @Override
            public GoTerm next() {
                if (nextGoTerm == null) {
                    throw new NoSuchElementException();
                }

                GoTerm result = nextGoTerm;
                nextGoTerm = readNextGoTerm();
                return result;
            }

            private GoTerm readNextGoTerm() {
                List<String> entryLines = new ArrayList<>();

                boolean termStarted = false;
                String currentLine;

                while (true) {
                    currentLine = fileLineIterator.hasNext() ? fileLineIterator.next() : null;

                    if (currentLine == null) {
                        break;
                    }

                    if (!termStarted) {
                        if (currentLine.equals("[Term]")) {
                            termStarted = true;
                            entryLines.add(currentLine);
                        }
                        continue;
                    }

                    if (currentLine.isBlank()) {
                        break;
                    }

                    entryLines.add(currentLine);
                }

                return termStarted ? parseGoTerm(entryLines) : null;
            }
        };
    }

    private List<String> getGoTermsFileLines() {
        return this.fileLines;
    }

    GoTerm parseGoTerm(List<String> entryLines) {
        return isGoTermEntryObsolete(entryLines) ? buildObsoleteGoTerm(entryLines) : buildGoTerm(entryLines);
    }

    private boolean isGoTermEntryObsolete(List<String> entryLines) {
        return entryLines.stream().anyMatch(line -> line.contains("is_obsolete: true"));
    }

    private ObsoleteGoTerm buildObsoleteGoTerm(List<String> entryLines) {
        String id = parseId(entryLines);
        String name = parseName(entryLines);
        GONamespace namespace = parseNameSpace(entryLines);
        String def = parseDef(entryLines);

        return new ObsoleteGoTerm.Builder(id, name, namespace, def)
            .withAlternateIds(parseAlternateIds(entryLines))
            .withSynonyms(parseSynonyms(entryLines))
            .withIsA(parseIsA(entryLines))
            .withPartOf(parsePartOf(entryLines))
            .withHasPart(parseHasPart(entryLines))
            .withEcNumber(getECNumbers(id))
            .withConsider(parseConsider(entryLines))
            .withReplacedBy(parseReplacedBy(entryLines))
            .build();
    }

    private GoTerm buildGoTerm(List<String> entryLines) {
        String id = parseId(entryLines);
        String name = parseName(entryLines);
        GONamespace namespace = parseNameSpace(entryLines);
        String def = parseDef(entryLines);

        return new GoTerm.Builder(id, name, namespace, def)
            .withAlternateIds(parseAlternateIds(entryLines))
            .withSynonyms(parseSynonyms(entryLines))
            .withIsA(parseIsA(entryLines))
            .withPartOf(parsePartOf(entryLines))
            .withHasPart(parseHasPart(entryLines))
            .withEcNumber(getECNumbers(id))
            .build();
    }

    private String parseId(List<String> entryLines) {
        return removeGOPrefix(parseGenericStringValue("id: ", entryLines));
    }

    private String parseName(List<String>  entryLines) {
        return parseGenericStringValue("name: ", entryLines);
    }

    private GONamespace parseNameSpace(List<String> entryLines) {
        return GONamespace.valueOf(parseGenericStringValue("namespace: ", entryLines));
    }

    private String parseDef(List<String> entryLines) {
        return parseGenericStringValue("def: ", entryLines);
    }

    private List<String> parseConsider(List<String> entryLines) {
        return removeGOPrefixes(parseGenericStringList("consider: ", entryLines));
    }

    private String parseReplacedBy(List<String> entryLines) {
        return removeGOPrefix(parseGenericStringValue("replaced_by: ", entryLines));
    }

    private List<String> parseAlternateIds(List<String> entryLines) {
        return removeGOPrefixes(parseGenericStringList("alt_id: ", entryLines));
    }

    private List<String> parseSynonyms(List<String> entryLines) {
        return parseGenericStringList("synonyms: ", entryLines)
            .stream()
            .map(synonym -> {
                Pattern valueInQuotesPattern = Pattern.compile("\"(.*)\"");
                Matcher valueInQuotesMatcher = valueInQuotesPattern.matcher(synonym);
                if (valueInQuotesMatcher.find()) {
                    return valueInQuotesMatcher.group(1);
                } else {
                    throw new RuntimeException("Unable to find GO term for isA in " + entryLines);
                }
            })
            .collect(Collectors.toList());
    }

    private List<String> parseIsA(List<String> entryLines) {
        return parseGenericStringList("is_a: ", entryLines)
            .stream()
            .map(isA -> {
                Pattern goTermOnlyPattern = Pattern.compile("GO:(\\d+)");
                Matcher goTermOnlyMatcher = goTermOnlyPattern.matcher(isA);
                if (goTermOnlyMatcher.find()) {
                    return goTermOnlyMatcher.group(1);
                } else {
                    throw new RuntimeException("Unable to find GO term for isA in " + entryLines);
                }
            })
            .collect(Collectors.toList());
    }

    private List<String> parsePartOf(List<String> entryLines) {
        return parseGenericStringList("relationship: part_of ", entryLines)
            .stream()
            .map(partOf -> {
                Pattern goTermOnlyPattern = Pattern.compile("GO:(\\d+)");
                Matcher goTermOnlyMatcher = goTermOnlyPattern.matcher(partOf);
                if (goTermOnlyMatcher.find()) {
                    return goTermOnlyMatcher.group(1);
                } else {
                    throw new RuntimeException("Unable to find GO term for partOf in " + entryLines);
                }
            })
            .collect(Collectors.toList());
    }

    private List<String> parseHasPart(List<String> entryLines) {
        return parseGenericStringList("relationship: has_part ", entryLines)
            .stream()
            .map(partOf -> {
                Pattern goTermOnlyPattern = Pattern.compile("GO:(\\d+)");
                Matcher goTermOnlyMatcher = goTermOnlyPattern.matcher(partOf);
                if (goTermOnlyMatcher.find()) {
                    return goTermOnlyMatcher.group(1);
                } else {
                    throw new RuntimeException("Unable to find GO term for hasPart in " + entryLines);
                }
            })
            .collect(Collectors.toList());
    }

    private String getECNumbers(String goId) {
        List<String> ecNumbers = this.goAccession2ECNumbers.computeIfAbsent(goId, k -> new ArrayList<>());
        return !ecNumbers.isEmpty() ? ecNumbers.get(0) : "";
    }

    private List<String> parseGenericStringList(String attributePrefix, List<String> entryLines) {
        return entryLines.stream()
            .filter(line -> line.contains(attributePrefix))
            .map(line -> line.replace(attributePrefix, ""))
            .collect(Collectors.toList());
    }

    private String parseGenericStringValue(String attributePrefix, List<String> entryLines) {
        return entryLines.stream()
            .filter(line -> line.contains(attributePrefix))
            .map(line -> line.replace(attributePrefix,""))
            .findFirst()
            .orElse("");
    }

    private List<String> removeGOPrefixes(List<String> goAccessions) {
        return goAccessions
            .stream()
            .map(this::removeGOPrefix)
            .collect(Collectors.toList());
    }

    private String removeGOPrefix(String goAccession) {
        return goAccession.replace("GO:", "");
    }
}


