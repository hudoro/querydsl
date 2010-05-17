/*
 * Copyright (c) 2010 Mysema Ltd.
 * All rights reserved.
 *
 */
package com.mysema.query.lucene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.util.NumericUtils;

import com.mysema.query.types.Constant;
import com.mysema.query.types.Expr;
import com.mysema.query.types.Operation;
import com.mysema.query.types.Operator;
import com.mysema.query.types.Ops;
import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.Path;

/**
 * Serializes Querydsl queries to Lucene queries.
 *
 * @author vema
 *
 */
// TODO Add support for longs, floats etc.
public class LuceneSerializer {

    public static final LuceneSerializer DEFAULT = new LuceneSerializer(false, true);

    private final boolean lowerCase;

    private final boolean splitTerms;

    protected LuceneSerializer(boolean lowerCase, boolean splitTerms) {
        this.lowerCase = lowerCase;
        this.splitTerms = splitTerms;
    }

    private Query toQuery(Operation<?> operation) {
        Operator<?> op = operation.getOperator();
        if (op == Ops.OR) {
            return toTwoHandSidedQuery(operation, Occur.SHOULD);
        } else if (op == Ops.AND) {
            return toTwoHandSidedQuery(operation, Occur.MUST);
        } else if (op == Ops.NOT) {
            BooleanQuery bq = new BooleanQuery();
            bq.add(new BooleanClause(toQuery(operation.getArg(0)), Occur.MUST_NOT));
            return bq;
        } else if (op == Ops.LIKE) {
            return like(operation);
        } else if (op == Ops.EQ_OBJECT || op == Ops.EQ_PRIMITIVE || op == Ops.EQ_IGNORE_CASE) {
            return eq(operation);
        } else if (op == Ops.NE_OBJECT || op == Ops.NE_PRIMITIVE) {
            return ne(operation);
        } else if (op == Ops.STARTS_WITH || op == Ops.STARTS_WITH_IC) {
            return startsWith(operation);
        } else if (op == Ops.ENDS_WITH || op == Ops.ENDS_WITH_IC) {
            return endsWith(operation);
        } else if (op == Ops.STRING_CONTAINS || op == Ops.STRING_CONTAINS_IC) {
            return stringContains(operation);
        } else if (op == Ops.BETWEEN) {
            return between(operation);
        } else if (op == Ops.IN) {
            return in(operation);
        } else if (op == Ops.LT || op == Ops.BEFORE) {
            return lt(operation);
        } else if (op == Ops.GT || op == Ops.AFTER) {
            return gt(operation);
        } else if (op == Ops.LOE || op == Ops.BOE) {
            return le(operation);
        } else if (op == Ops.GOE || op == Ops.AOE) {
            return ge(operation);
        }
        throw new UnsupportedOperationException("Illegal operation " + operation);
    }

    private Query toTwoHandSidedQuery(Operation<?> operation, Occur occur) {
        // TODO Flatten similar queries(?)
        Query lhs = toQuery(operation.getArg(0));
        Query rhs = toQuery(operation.getArg(1));
        BooleanQuery bq = new BooleanQuery();
        bq.add(lhs, occur);
        bq.add(rhs, occur);
        return bq;
    }

    private Query like(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + normalize(s) + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, normalize(terms[0])));
    }

    private Query eq(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        if (operation.getArg(1).getType().equals(Integer.class)) {
            return new TermQuery(new Term(field, NumericUtils
                    .intToPrefixCoded(((Constant<Integer>) operation.getArg(1)).getConstant())));
        } else if (operation.getArg(1).getType().equals(Double.class)) {
            return new TermQuery(new Term(field, NumericUtils
                    .doubleToPrefixCoded(((Constant<Double>) operation.getArg(1)).getConstant())));
        }
        return eq(field, createTerms(operation.getArg(1)));
    }

    private Query eq(String field, String[] terms) {
        if (terms.length > 1) {
            PhraseQuery pq = new PhraseQuery();
            for (String s : terms) {
                pq.add(new Term(field, normalize(s)));
            }
            return pq;
        }
        return new TermQuery(new Term(field, normalize(terms[0])));
    }

    @SuppressWarnings("unchecked")
    private Query in(Operation<?> operation) {
        String field = toField(operation.getArg(0));
        Collection values = (Collection) ((Constant) operation.getArg(1)).getConstant();
        BooleanQuery bq = new BooleanQuery();
        for (Object value : values) {
            bq.add(eq(field, StringUtils.split(value.toString())), Occur.SHOULD);
        }
        return bq;
    }

    private Query ne(Operation<?> operation) {
        BooleanQuery bq = new BooleanQuery();
        bq.add(new BooleanClause(eq(operation), Occur.MUST_NOT));
        return bq;
    }

    private Query startsWith(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == 0 ? terms[i] + "*" : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, normalize(s))), Occur.MUST);
            }
            return bq;
        }
        return new PrefixQuery(new Term(field, normalize(terms[0])));
    }

    private Query stringContains(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + normalize(s) + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + normalize(terms[0]) + "*"));
    }

    private Query endsWith(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == terms.length - 1 ? "*" + terms[i] : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, normalize(s))), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + normalize(terms[0])));
    }

    private Query between(Operation<?> operation) {
        verifyArguments(operation);
        // TODO Phrase not properly supported
        return range(toField(operation.getArg(0)), operation.getArg(1), operation.getArg(2), true, true);
    }

    private Query lt(Operation<?> operation) {
        verifyArguments(operation);
        return range(toField(operation.getArg(0)), null, operation.getArg(1), false, false);
    }

    private Query gt(Operation<?> operation) {
        verifyArguments(operation);
        return range(toField(operation.getArg(0)), operation.getArg(1), null, false, false);
    }

    private Query le(Operation<?> operation) {
        verifyArguments(operation);
        return range(toField(operation.getArg(0)), null, operation.getArg(1), true, true);
    }

    private Query ge(Operation<?> operation) {
        verifyArguments(operation);
        return range(toField(operation.getArg(0)), operation.getArg(1), null, true, true);
    }

    // TODO Simplify(?)
    // TODO Timo: Check if the the annotation is necessary, thanks! -vema
    @SuppressWarnings("unchecked")
    private Query range(String field, Expr<?> min, Expr<?> max, boolean minInc, boolean maxInc) {
        if (min != null && min.getType().equals(Integer.class) || max != null && max.getType().equals(Integer.class)) {
            return integerRange(field, min == null ? null : ((Constant<Integer>) min).getConstant(), max == null ? null : ((Constant<Integer>) max).getConstant(), minInc, maxInc);
        } else if (min != null && min.getType().equals(Double.class) || max != null && max.getType().equals(Double.class)) {
            return doubleRange(field, min == null ? null : ((Constant<Double>) min).getConstant(), max == null ? null : ((Constant<Double>) max).getConstant(), minInc, maxInc);
        } else {
            return stringRange(field, min, max, minInc, maxInc);
        }
    }

    private Query integerRange(String field, Integer min, Integer max, boolean minInc, boolean maxInc) {
        return NumericRangeQuery.newIntRange(field, min, max, minInc, maxInc);
    }

    private Query doubleRange(String field, Double min, Double max, boolean minInc, boolean maxInc) {
        return NumericRangeQuery.newDoubleRange(field, min, max, minInc, maxInc);
    }

    private Query stringRange(String field, Expr<?> min, Expr<?> max, boolean minInc, boolean maxInc) {
        if (min == null) {
            return new TermRangeQuery(field, null, normalize(createTerms(max)[0]), minInc,
                    maxInc);
        } else if (max == null) {
            return new TermRangeQuery(field, normalize(createTerms(min)[0]), null, minInc,
                    maxInc);
        }
        return new TermRangeQuery(field, normalize(createTerms(min)[0]), normalize(createTerms(max)[0]), minInc,
                maxInc);
    }

    @SuppressWarnings("unchecked")
    private String toField(Expr<?> expr) {
        if (expr instanceof Path) {
            return toField((Path<?>) expr);
        } else if (expr instanceof Operation) {
            Operation<?> operation = (Operation<?>) expr;
            if (operation.getOperator() == Ops.LOWER || operation.getOperator() == Ops.UPPER) {
                return toField(operation.getArg(0));
            }
        }
        throw new IllegalArgumentException("Unable to transform " + expr + " to field");
    }

    public String toField(Path<?> path) {
        return path.getMetadata().getExpression().toString();
    }

    private void verifyArguments(Operation<?> operation) {
        List<Expr<?>> arguments = operation.getArgs();
        for (int i = 1; i < arguments.size(); ++i) {
            if (!(arguments.get(i) instanceof Constant<?>)
                    && !(arguments.get(i) instanceof PhraseElement)) {
                throw new IllegalArgumentException(
                        "operation argument was not of type Constant nor PhraseElement.");
            }
        }
    }

    private String[] createTerms(Expr<?> expr) {
        if (splitTerms || expr instanceof PhraseElement) {
            return StringUtils.split(expr.toString());
        }
        return new String[] { expr.toString() };
    }

    private String[] createEscapedTerms(Expr<?> expr) {
        String escaped = QueryParser.escape(expr.toString());
        if (splitTerms || expr instanceof PhraseElement) {
            return StringUtils.split(escaped);
        }
        return new String[] { escaped };
    }

    private String normalize(String s) {
        return lowerCase ? s.toLowerCase(Locale.ENGLISH) : s;
    }

    public Query toQuery(Expr<?> expr) {
        if (expr instanceof Operation<?>) {
            return toQuery((Operation<?>) expr);
        } else if (expr instanceof QueryElement) {
            return ((QueryElement) expr).getQuery();
        }
        throw new IllegalArgumentException("expr was not of type Operation or QueryElement");
    }

    // TODO Add support for sorting floats, longs etc.
    public Sort toSort(List<OrderSpecifier<?>> orderBys) {
        List<SortField> sortFields = new ArrayList<SortField>(orderBys.size());
        for (OrderSpecifier<?> orderSpecifier : orderBys) {
            if (!(orderSpecifier.getTarget() instanceof Path<?>)) {
                throw new IllegalArgumentException("argument was not of type Path.");
            }
            if (orderSpecifier.getTarget().getType().equals(Integer.class)) {
                sortFields.add(new SortField(toField((Path<?>) orderSpecifier.getTarget()),
                        SortField.INT, !orderSpecifier.isAscending()));
            } else if (orderSpecifier.getTarget().getType().equals(Double.class)) {
                sortFields.add(new SortField(toField((Path<?>) orderSpecifier.getTarget()),
                        SortField.DOUBLE, !orderSpecifier.isAscending()));
            } else {
                sortFields.add(new SortField(toField((Path<?>) orderSpecifier.getTarget()),
                        Locale.ENGLISH, !orderSpecifier.isAscending()));
            }
        }
        Sort sort = new Sort();
        sort.setSort(sortFields.toArray(new SortField[sortFields.size()]));
        return sort;
    }
}
