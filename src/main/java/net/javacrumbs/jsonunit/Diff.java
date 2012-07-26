/**
 * Copyright 2009-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.jsonunit;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;


/**
 * Compares JSON structures.
 * @author Lukas Krecan
 * @author Jan Marcis
 *
 */
class Diff {
	private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\w+)\\[(\\d+)\\]");
    private static final String SAME_VALUE = "JSON documents have the same value.";
    private final JsonNode expectedRoot;
    private final JsonNode actualRoot;
    private final Differences structureDifferences = new Differences("structures");
    private final Differences valueDifferences = new Differences("values");
    private final String startPath;
    private boolean compared = false;
    static String IGNORE_VALUE_CONSTANT;
    static String ANY_STRING_CONSTANT;
    static String ANY_NUMBER_CONSTANT;
    static String ANY_DATE_CONSTANT;
    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

	private enum NodeType {OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, DATE};

	public Diff(JsonNode expected, JsonNode actual, String startPath) {
		super();
		this.expectedRoot = expected;
		this.actualRoot = actual;
		this.startPath = startPath;
	}


    private void compare() {
		if ( ! compared) {
			JsonNode part = getStartNode(actualRoot, startPath);
			if (part.isMissingNode()) {
				structureDifferenceFound("Missing node in path \"%s\".", startPath);
			} else {
				compareNodes(expectedRoot, part, startPath);
			}
			compared = true;
		}
	}

	static JsonNode getStartNode(JsonNode actualRoot, String startPath) {
		if (startPath.isEmpty()) {
			return actualRoot;
		}

		JsonNode startNode = actualRoot;
		StringTokenizer stringTokenizer = new StringTokenizer(startPath, ".");
		while (stringTokenizer.hasMoreElements()) {
			String step = stringTokenizer.nextToken();
			Matcher matcher = ARRAY_PATTERN.matcher(step);
			if (!matcher.matches()) {
				startNode = startNode.path(step);
			} else {
				startNode = startNode.path(matcher.group(1));
				startNode = startNode.path(Integer.valueOf(matcher.group(2)));
			}
		}
		return startNode;
	}


	/**
	 * Compares object nodes.
	 * @param expected
	 * @param actual
	 * @param path
	 */
	private void compareObjectNodes(ObjectNode expected, ObjectNode actual, String path) {
		Map<String, JsonNode> expectedFields = getFields(expected);
		Map<String, JsonNode> actualFields = getFields(actual);

		if (!expectedFields.keySet().equals(actualFields.keySet())) {
			structureDifferenceFound("Different keys found in node \"%s\". Expected %s, got %s.", path, sort(expectedFields.keySet()), sort(actualFields.keySet()));
		}

		for (String fieldName : commonFields(expectedFields, actualFields)) {
			JsonNode expectedNode = expectedFields.get(fieldName);
			JsonNode actualNode = actualFields.get(fieldName);
			String fieldPath = getPath(path, fieldName);
			compareNodes(expectedNode, actualNode, fieldPath);
		}
	}



	/**
	 * Compares two nodes.
	 * @param expectedNode
	 * @param actualNode
	 * @param fieldPath
	 */
	private void compareNodes(JsonNode expectedNode, JsonNode actualNode, String fieldPath) {
        NodeType phNodeType = getExpectedNodeTypeFromPlaceHolder(expectedNode);

		NodeType expectedNodeType = phNodeType == null ? getNodeType(expectedNode) : phNodeType;
		NodeType actualNodeType = getNodeType(actualNode);
        // NodeType DATE is subtype of String
        if(NodeType.STRING.equals(actualNodeType) && isDate(actualNode.getTextValue())) {
            actualNodeType = NodeType.DATE;
        }
        // do not compare type and value for ignore-placeholder
        if(NodeType.STRING.equals(expectedNodeType) && expectedNode.getTextValue().equals(IGNORE_VALUE_CONSTANT)) {
            return;
        }
		if (!expectedNodeType.equals(actualNodeType)) {
            // I would expect different type message here, not different values...
			valueDifferenceFound("Different values found in node \"%s\". Expected '%s', got '%s'.", fieldPath, expectedNode, actualNode);
		} else {
            // do not compare value for ANY_ placeholders
            if(phNodeType != null) return;

			switch (expectedNodeType) {
				case OBJECT:
					compareObjectNodes((ObjectNode)expectedNode, (ObjectNode)actualNode, fieldPath);
					break;
				case ARRAY:
					compareArrayNodes((ArrayNode)expectedNode, (ArrayNode)actualNode, fieldPath);
					break;
				case STRING:
					compareValues(expectedNode.getTextValue(), actualNode.getTextValue(), fieldPath);
					break;
				case NUMBER:
					compareValues(expectedNode.getNumberValue(), actualNode.getNumberValue(), fieldPath);
					break;
				case BOOLEAN:
					compareValues(expectedNode.getBooleanValue(), actualNode.getBooleanValue(), fieldPath);
					break;
				case NULL:
					//nothing
					break;
                case DATE:
                    // nothing
                    break;
				default:
					throw new IllegalStateException("Unexpected node type "+expectedNodeType);
			}
		}
	}

    private boolean isDate(String nodeTextValue) {
        SimpleDateFormat format = new SimpleDateFormat(DATE_TIME_FORMAT);
        try {
            format.parse(nodeTextValue);  // do not need the value
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    private NodeType getExpectedNodeTypeFromPlaceHolder(JsonNode expectedNode) {
        // placeholders are only Strings
        if(!NodeType.STRING.equals(getNodeType(expectedNode))) return null;

        if(ANY_NUMBER_CONSTANT.equals(expectedNode.getTextValue())) {
            return NodeType.NUMBER;
        } else if(ANY_STRING_CONSTANT.equals(expectedNode.getTextValue())) {
            return NodeType.STRING;
        } else if(ANY_DATE_CONSTANT.equals(expectedNode.getTextValue())) {
            return NodeType.DATE;
        }
        return null;
    }



	private void compareValues(Object expectedValue, Object actualValue, String path) {
        // do not compare values for placeholders
        if(isPlaceHolder(expectedValue)) return;

		if (!expectedValue.equals(actualValue)) {
			valueDifferenceFound("Different value found in node \"%s\". Expected %s, got %s.", path, expectedValue, actualValue);
		}
	}

    private boolean isPlaceHolder(Object value) {
        // placeholders are strings
        if(!(value instanceof String)) return false;

        String string = (String) value;
        if(ANY_NUMBER_CONSTANT.equals(string)) return true;
        if(ANY_STRING_CONSTANT.equals(string)) return true;
        return false;
    }


	private void compareArrayNodes(ArrayNode expectedNode, ArrayNode actualNode, String path) {
		List<JsonNode> expectedElements = asList(expectedNode.getElements());
		List<JsonNode> actualElements = asList(actualNode.getElements());
		if (expectedElements.size()!=actualElements.size()) {
			structureDifferenceFound("Array \"%s\" has different length. Expected %d, got %d.", path, expectedElements.size(), actualElements.size());
		}
		for (int i=0; i<Math.min(expectedElements.size(), actualElements.size()); i++) {
			compareNodes(expectedElements.get(i), actualElements.get(i), getArrayPath(path, i));
		}
	}


	private List<JsonNode> asList(Iterator<JsonNode> elements) {
		List<JsonNode> result = new ArrayList<JsonNode>();
		while (elements.hasNext()) {
			JsonNode jsonNode = (JsonNode) elements.next();
			result.add(jsonNode);
		}
		return Collections.unmodifiableList(result);
	}


    /**
     * Returns NodeType of the node.
     *
     * @param node
     * @return
     */
    private NodeType getNodeType(JsonNode node) {
        if (node.isObject()) {
            return NodeType.OBJECT;
        } else if (node.isArray()) {
            return NodeType.ARRAY;
        } else if (node.isTextual()) {
            return NodeType.STRING;
        } else if (node.isNumber()) {
            return NodeType.NUMBER;
        } else if (node.isBoolean()) {
            return NodeType.BOOLEAN;
        } else if (node.isNull()) {
            return NodeType.NULL;
        } else {
            throw new IllegalStateException("Unexpected node type " + node);
        }
    }


    /**
	 * Construct path to an element.
	 * @param parent
	 * @param name
	 * @return
	 */
	private String getPath(String parent, String name) {
		if (parent.isEmpty()) {
			return name;
		} else {
			return parent+"."+name;
		}
	}

	/**
	 * Constructs path to an array element.
	 * @param parent
	 * @param i
	 * @return
	 */
	private String getArrayPath(String parent, int i) {
		if (parent.isEmpty()) {
			return "["+i+"]";
		} else {
			return parent+"["+i+"]";
		}
	}

	private void structureDifferenceFound(String message, Object... arguments) {
		structureDifferences.add(message, arguments);
	}

	private void valueDifferenceFound(String message, Object... arguments) {
		valueDifferences.add(message, arguments);
	}


	private Set<String> commonFields(Map<String, JsonNode> expectedFields, Map<String, JsonNode> actualFields) {
		Set<String> result = new TreeSet<String>(expectedFields.keySet());
		result.retainAll(actualFields.keySet());
		return Collections.unmodifiableSet(result);
	}


	private final SortedSet<String> sort(Set<String> set) {
		return new TreeSet<String>(set);
	}

	public boolean similarStructure() {
		compare();
		return structureDifferences.isEmpty();
	}

	public boolean similar() {
		return similarStructure() && valueDifferences.isEmpty();
	}

	/**
	 * Returns children of an ObjectNode.
	 * @param node
	 * @return
	 */
	private static Map<String, JsonNode> getFields(ObjectNode node) {
		Map<String, JsonNode> result = new HashMap<String, JsonNode>();
		Iterator<Entry<String, JsonNode>> fields = node.getFields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = (Map.Entry<String, JsonNode>) fields.next();
			result.put(field.getKey(), field.getValue());
		}
		return Collections.unmodifiableMap(result);
	}

	@Override
	public String toString() {
		return differences();
	}

	public String differences() {
		if (similar()) {
		    return SAME_VALUE;
		}
		StringBuilder message = new StringBuilder();
		structureDifferences.appendDifferences(message);
		valueDifferences.appendDifferences(message);
		return message.toString();
	}

	public String valueDifferences() {
		if (similarStructure()) {
		    return SAME_VALUE;
		}
		StringBuilder message = new StringBuilder();
		valueDifferences.appendDifferences(message);
		return message.toString();
	}

	public String structureDifferences() {
		if (similarStructure()) {
		    return "JSON documents have the same structure.";
		}
		StringBuilder message = new StringBuilder();
		structureDifferences.appendDifferences(message);
		return message.toString();
	}

}
