package org.aksw.hawk.querybuilding;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aksw.autosparql.commons.qald.Question;
import org.aksw.hawk.index.DBAbstractsIndex;
import org.aksw.hawk.nlp.MutableTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hp.hpl.jena.rdf.model.RDFNode;

public class SPARQLQueryBuilder {
	Logger log = LoggerFactory.getLogger(SPARQLQueryBuilder.class);
	SPARQLQueryBuilder_ProjectionPart projection = new SPARQLQueryBuilder_ProjectionPart();
	SPARQL sparql = new SPARQL();
	DBAbstractsIndex index;

	public SPARQLQueryBuilder(DBAbstractsIndex index) {
		this.index = index;
	}

	public Map<String, Set<RDFNode>> build(Question q) {
		Map<String, Set<RDFNode>> answer = Maps.newHashMap();
		try {
			if (q.languageToQuestion.get("en").contains("philosopher")) {
				System.out.println();
			}
			// build projection part
			Set<SPARQLQuery> queryStrings = projection.buildProjectionPart(this, q);
			System.gc();
			queryStrings = buildRootPart(queryStrings, q);
			System.gc();
			queryStrings = buildConstraintPart(queryStrings, q);
			System.gc();
			int i = 0;
			for (SPARQLQuery queryString : queryStrings) {
				String query = queryString.toString();
				log.debug(i++ + "/" + queryStrings.size() + "= " + query.substring(0, Math.min(1000, query.length())));
				Set<RDFNode> answerSet = sparql.sparql(query);
				if (!answerSet.isEmpty()) {
					answer.put(query, answerSet);
				}
			}
		} catch (CloneNotSupportedException e) {
			log.error(e.getLocalizedMessage(), e);
		}
		return answer;
	}

	private Set<SPARQLQuery> buildConstraintPart(Set<SPARQLQuery> queryStrings, Question q) throws CloneNotSupportedException {
		Set<SPARQLQuery> sb = Sets.newHashSet();
		//TODO only valid for questions with one constraint node
		if (q.tree.getRoot().getChildren().size() == 2&&q.tree.getRoot().getChildren().get(1).getChildren().isEmpty()) {
			log.info(q.tree.toString());
			MutableTreeNode tmp = q.tree.getRoot().getChildren().get(1);

			if (tmp.posTag.equals("ADD")) {
				for (SPARQLQuery query : queryStrings) {
					// GIVEN ?proj ?root ?const or ?const ?root ?proj
					// TODO ??? && !tmp.getAnnotations().isEmpty()
					if (query.constraintsContains("?const")) {
						SPARQLQuery variant1 = (SPARQLQuery) query.clone();
						variant1.addConstraint("?proj ?pbridge <" + tmp.label + ">.");
						sb.add(variant1);
						SPARQLQuery variant2 = (SPARQLQuery) query.clone();
						variant2.addFilter("const", Lists.newArrayList(tmp.label));
						sb.add(variant2);
					}
					// GIVEN no constraint yet given and root incapable for those purposes
					else {
						SPARQLQuery variant2 = (SPARQLQuery) query.clone();
						variant2.addConstraint("?proj ?pbridge <" + tmp.label + ">.");
						sb.add(variant2);
					}
				}
			} else if (tmp.posTag.equals("CombinedNN")) {
				for (SPARQLQuery query : queryStrings) {
					if (!tmp.getAnnotations().isEmpty()) {
						SPARQLQuery variant1 = (SPARQLQuery) query.clone();
						variant1.addFilter("proj", tmp.getAnnotations());
						sb.add(variant1);

						SPARQLQuery variant2 = (SPARQLQuery) query.clone();
						variant2.addFilter("const", tmp.getAnnotations());
						sb.add(variant2);
					}
				}
			} else {
				log.error("unhandled path");
				// TODO go on here

				// sb.addAll(queryStrings);
			}
		} else {
			log.error("more children than expected");
			// TODO go on here
			// sb.addAll(queryStrings);
		}
		return sb;

	}

	private Set<SPARQLQuery> buildRootPart(Set<SPARQLQuery> queryStrings, Question q) throws CloneNotSupportedException {
		Set<SPARQLQuery> sb = Sets.newHashSet();
		MutableTreeNode root = q.tree.getRoot();

		// full-text stuff e.g. "protected"
		List<String> uris = index.listAbstractsContaining(root.label);
		if (!root.getAnnotations().isEmpty()) {
			for (SPARQLQuery query : queryStrings) {
				for (String anno : root.getAnnotations()) {
					// root has a valuable annotation from NN* or VB*
					SPARQLQuery variant1 = ((SPARQLQuery) query.clone());
					variant1.addConstraint("?proj  <" + anno + "> ?const.");

					SPARQLQuery variant2 = ((SPARQLQuery) query.clone());
					variant2.addConstraint("?const <" + anno + "> ?proj.");

					// root has annotations but they are not valuable, e.g. took, is, was, ride
					SPARQLQuery variant3 = ((SPARQLQuery) query.clone());
					variant3.addConstraint("?const  ?p ?proj.");

					SPARQLQuery variant4 = ((SPARQLQuery) query.clone());
					variant4.addConstraint("?proj   ?p ?const.");

					// TODO vllt &&uris.size()< 100?
					// otherwise this filter will explode
					if (!uris.isEmpty() && uris.size() < 100) {
						SPARQLQuery variant5 = ((SPARQLQuery) query.clone());
						variant5.addFilter("proj", uris);
						sb.add(variant5);
					}
					sb.add(variant1);
					sb.add(variant2);
					sb.add(variant3);
					sb.add(variant4);
					// TODO build other variants

				}
			}
		} else {
			// TODO do the full text stuff
			sb.addAll(queryStrings);
		}
		return sb;
	}

}
