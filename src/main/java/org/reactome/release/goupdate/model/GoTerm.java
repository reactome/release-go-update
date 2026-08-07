package org.reactome.release.goupdate.model;

import org.reactome.release.goupdate.GONamespace;

import java.util.List;

public class GoTerm {
    private String id;
    private String name;
    private GONamespace namespace;
    private String def;
    private List<String> alternateIds;
    private List<String> synonyms;
    private List<String> isA;
    private List<String> partOf;
    private List<String> hasPart;
    private String ecNumber;

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public GONamespace getNamespace() {
        return this.namespace;
    }

    public String getDef() {
        return this.def;
    }

    public List<String> getAlternateIds() {
        return this.alternateIds;
    }

    public List<String> getSynonyms() {
        return this.synonyms;
    }

    public List<String> getIsA() {
        return this.isA;
    }

    public List<String> getPartOf() {
        return this.partOf;
    }

    public List<String> getHasPart() {
        return this.hasPart;
    }

    public String getEcNumber() {
        return this.ecNumber;
    }

    public static class Builder<T extends Builder<T>> {
        private String id;
        private String name;
        private GONamespace namespace;
        private String def;
        private List<String> alternateIds;
        private List<String> synonyms;
        private List<String> isA;
        private List<String> partOf;
        private List<String> hasPart;
        private String ecNumber;

        public Builder(String id, String name, GONamespace namespace, String def) {
            this.id = id;
            this.name = name;
            this.namespace = namespace;
            this.def = def;
        }

        public T withAlternateIds(List<String> alternateIds) {
            this.alternateIds = alternateIds;
            return self();
        }

        public T withSynonyms(List<String> synonyms) {
            this.synonyms = synonyms;
            return self();
        }

        public T withIsA(List<String> isA) {
            this.isA = isA;
            return self();
        }

        public T withPartOf(List<String> partOf) {
            this.partOf = partOf;
            return self();
        }

        public T withHasPart(List<String> hasPart) {
            this.hasPart = hasPart;
            return self();
        }

        public T withEcNumber(String ecNumber) {
            this.ecNumber = ecNumber;
            return self();
        }

        protected T self() {
            return (T) this;
        }

        public GoTerm build() {
            return new GoTerm(this);
        }
    }

    protected GoTerm(Builder<?> builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.namespace = builder.namespace;
        this.def = builder.def;
        this.alternateIds = builder.alternateIds;
        this.synonyms = builder.synonyms;
        this.isA = builder.isA;
        this.partOf = builder.partOf;
        this.hasPart = builder.hasPart;
        this.ecNumber = builder.ecNumber;
    }
}
