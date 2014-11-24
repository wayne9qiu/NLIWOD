package org.aksw.hawk.querybuilding;

import java.util.Map;
import java.util.Set;

import org.aksw.autosparql.commons.qald.Question;
import org.aksw.hawk.nlp.MutableTreeNode;
import org.aksw.hawk.pruner.DisjointnessBasedQueryFilter;
import org.aksw.hawk.pruner.GraphNonSCCPruner;
import org.aksw.hawk.pruner.UnboundTriple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hp.hpl.jena.rdf.model.RDFNode;

public class SPARQLQueryBuilder {
	int numberOfOverallQueriesExecuted = 0;
	Logger log = LoggerFactory.getLogger(SPARQLQueryBuilder.class);
	SPARQLQueryBuilder_ProjectionPart projection;
	SPARQLQueryBuilder_RootPart root;
	private SPARQL sparql;
	

	public SPARQLQueryBuilder(SPARQL sparql) {
		this.projection = new SPARQLQueryBuilder_ProjectionPart();
		this.root = new SPARQLQueryBuilder_RootPart();
		this.sparql = sparql;
	}

	public Map<String, Set<RDFNode>> build(Question q) {
		Map<String, Set<RDFNode>> answer = Maps.newHashMap();
		try {
			// build projection part
			Set<SPARQLQuery> queryStrings = projection.buildProjectionPart(this, q);
			queryStrings = root.buildRootPart(queryStrings, q);
			queryStrings = buildConstraintPart(queryStrings, q);

			// Pruning
			log.info("Number of Queries before pruning: " + queryStrings.size());
			// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11
			// FIXME disjointness killt die richtige antwort für philosopher
			// ohne die filter werden mehr fragen richtig beantwortet aber das
			// programm zerbricht!!!!!!!!!!!!
			// scc scheint die böse Query durchzulassen und disjointness auch
			// virtuoso ist halt kacke
			// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11
			// FIXME influence of pruner?

			GraphNonSCCPruner gSCCPruner = new GraphNonSCCPruner();
			DisjointnessBasedQueryFilter filter = new DisjointnessBasedQueryFilter(sparql.qef);
			queryStrings = filter.filter(queryStrings);
			queryStrings = gSCCPruner.prune(queryStrings);
			queryStrings = UnboundTriple.prune(queryStrings, 1);
//			queryStrings = UnboundTriple.pruneLooseEndsOfBGP(queryStrings);
			log.info("Number of Queries: " + queryStrings.size());

			int i = 0;
			for (SPARQLQuery query : queryStrings) {
				if (queryHasBoundVariables(query)) {
					log.debug(i++ + "/" + queryStrings.size() + "= " + query.toString());
					Set<RDFNode> answerSet = sparql.sparql(query);
					if (!answerSet.isEmpty()) {
						answer.put(query.toString(), answerSet);
					}
					numberOfOverallQueriesExecuted++;
				}
			}
		} catch (CloneNotSupportedException e) {
			log.error(e.getLocalizedMessage(), e);
		} catch (Exception e) {
			log.error(e.getLocalizedMessage(), e);
		} finally {
			System.gc();
		}
		log.info("Number of sofar executed queries: " + numberOfOverallQueriesExecuted);
		return answer;
	}

	private boolean queryHasBoundVariables(SPARQLQuery queryString) {
		for (String triple : queryString.constraintTriples) {
			if (triple.contains("http")) {
				return true;
			}
		}
		if (queryString.filter.isEmpty()) {
			return false;
		}
		return false;
	}

	private Set<SPARQLQuery> buildConstraintPart(Set<SPARQLQuery> queryStrings, Question q) throws CloneNotSupportedException {
		Set<SPARQLQuery> sb = Sets.newHashSet();
		// TODO only valid for questions with one constraint node
		if (q.tree.getRoot().getChildren().size() == 2) {
			MutableTreeNode tmp = q.tree.getRoot().getChildren().get(1);
			while (tmp != null) {
				if (!sb.isEmpty()) {
					// to be able to add to queries that have been created in
					// the last iteration, i.e., nodes that come before this
					// node in the constraint path
					queryStrings = sb;
					sb = Sets.newHashSet();

				}
				log.debug("Current node in constraint part: " + tmp);
				if (tmp.posTag.equals("ADD")) {
					for (SPARQLQuery query : queryStrings) {
						// GIVEN ?proj ?root ?const or ?const ?root ?proj
						if (query.constraintsContains("?const")) {
							SPARQLQuery variant1 = (SPARQLQuery) query.clone();
							variant1.addConstraint("?proj ?pbridge <" + tmp.label + ">.");
							sb.add(variant1);
							SPARQLQuery variant2 = (SPARQLQuery) query.clone();
							variant2.addFilter("?proj IN (<" + tmp.label + ">)");
							sb.add(variant2);
							SPARQLQuery variant3 = (SPARQLQuery) query.clone();
							sb.add(variant3);
						}
						// GIVEN no constraint yet given and root incapable for
						// those purposes
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
							variant1.addFilterOverAbstractsContraint("?proj", tmp.label);
							sb.add(variant1);

							SPARQLQuery variant2 = (SPARQLQuery) query.clone();
							variant2.addFilterOverAbstractsContraint("?const", tmp.label);
							sb.add(variant2);
						}
					}
				} else if (tmp.posTag.equals("NN")) {
					for (SPARQLQuery query : queryStrings) {
						if (!tmp.getAnnotations().isEmpty()) {
							for (String annotation : tmp.getAnnotations()) {
								SPARQLQuery variant1 = (SPARQLQuery) query.clone();
								variant1.addConstraint("?proj a <" + annotation + ">.");

								sb.add(variant1);
								SPARQLQuery variant2 = (SPARQLQuery) query.clone();
								variant2.addConstraint("?const a <" + annotation + ">.");
								sb.add(variant2);
							}
						}
					}
				} else if (tmp.posTag.equals("VBD")) {
					for (SPARQLQuery query : queryStrings) {
						SPARQLQuery variant1 = (SPARQLQuery) query.clone();
						variant1.addFilterOverAbstractsContraint("?proj", tmp.label);
						sb.add(variant1);
						SPARQLQuery variant2 = (SPARQLQuery) query.clone();
						variant2.addFilterOverAbstractsContraint("?const", tmp.label);
						sb.add(variant2);
					}
				} else {
					log.error("unhandled path");
				}
				if (!tmp.getChildren().isEmpty()) {
					if (tmp.getChildren().size() > 0) {
						tmp = tmp.getChildren().get(0);
					} else {
						log.error("More children in constraint part than expected");
					}
				} else {
					tmp = null;
				}
			}
		} else {
			log.error("more children than expected");
			// TODO go on here
			// sb.addAll(queryStrings);
		}
		return sb;

	}
}
