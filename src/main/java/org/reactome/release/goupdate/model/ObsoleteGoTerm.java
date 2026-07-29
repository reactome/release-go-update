package org.reactome.release.goupdate.model;

import org.reactome.release.goupdate.GONamespace;

import java.util.List;

public class ObsoleteGoTerm extends GoTerm {
    private List<String> consider;
    private String replacedBy;

    public static boolean isObsolete(GoTerm goTerm) {
        return goTerm instanceof ObsoleteGoTerm;
    }

    public List<String> getConsider() {
        return this.consider;
    }

    public boolean hasReplacedBy() {
        return !getReplacedBy().isEmpty();
    }

    public String getReplacedBy() {
        return this.replacedBy;
    }

    public String getReplacedByOrConsiderString() {
        String replaceBy = !getReplacedBy().isEmpty() ?
            "Replace by: " + getReplacedBy() : "";
        String consider = !getConsider().isEmpty() ?
            "Consider: " + String.join(", ", getConsider()) : "";

        String replacementTermString = replaceBy + consider;
        return !replacementTermString.isEmpty() ? replacementTermString : "N/A";
    }

    public static class Builder extends GoTerm.Builder<Builder> {
        private List<String> consider;
        private String replacedBy;

        public Builder(String id, String name, GONamespace namespace, String def) {
            super(id, name, namespace, def);
        }

        public Builder withConsider(List<String> consider) {
            this.consider = consider;
            return self();
        }

        public Builder withReplacedBy(String replacedBy) {
            this.replacedBy = replacedBy;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public ObsoleteGoTerm build() {
            return new ObsoleteGoTerm(this);
        }
    }

    private ObsoleteGoTerm(Builder builder) {
        super(builder);
        this.consider = builder.consider;
        this.replacedBy = builder.replacedBy;
    }
}
