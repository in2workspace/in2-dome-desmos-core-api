package es.in2.desmos.objectmothers;

public final class EntityMother {

    private EntityMother() {
    }

    public static final String PRODUCT_OFFERING_1 = """
            {
                "id": "urn:product-offering:d86735a6-0faa-463d-a872-00b97affa1cb",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:ProductOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c51"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                 }
            }""";

    public static final String PRODUCT_OFFERING_1_NULL_LIFECYCLESTATUS = """
            {
                "id": "urn:product-offering:d86735a6-0faa-463d-a872-00b97affa1cb",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:ProductOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c51"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                 }
            }""";

    public static final String PRODUCT_OFFERING_2 = """
            {
                "id": "urn:product-offering:ed9c56c8-a5ab-42cc-bc62-0fca69a30c87",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c52"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String PRODUCT_OFFERING_2_OLD = """
            {
                "id": "urn:product-offering:ed9c56c8-a5ab-42cc-bc62-0fca69a30c87",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.0"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c52"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String PRODUCT_OFFERING_3 = """
            {
                "id": "urn:product-offering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c53"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String PRODUCT_OFFERING_3_OLD = """
            {
                "id": "urn:product-offering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2020-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c53"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String PRODUCT_OFFERING_4 = """
            {
                "id": "urn:product-offering:3645a0de-d74f-42c5-86ab-e27ccbdf0a9c",
                "type": "product-offering",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c54"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String WITHOUT_VERSION = """
            {
                "id": "urn:product-offering:5",
                "type": "product-offering",
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                },
                "productOfferingPrice": {
                    "type": "Relationship",
                    "object": "urn:912efae1-7ff6-4838-89f3-cfedfdfa1c54"
                },
                "productSpecification": {
                    "type": "Property",
                    "value": {
                        "name": "1Gbps Broadband Spec",
                        "id": "spec-broadband-001"
                    }
                }
            }""";

    public static final String CATEGORY = """
            {
                "id": "urn:category:1",
                "type": "category",
                "version": {
                    "type": "Property",
                    "value": "1.2"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-01-01T00:00:00.000Z",
                        "endDateTime": "2099-01-01T00:00:00.000Z"
                    }
                },
                "lastUpdate": {
                    "type": "Property",
                    "value": "2024-09-05T12:00:00Z"
                }
            }""";

    public static String[] scorpioFullJsonArray() {
        return new String[]{
                PRODUCT_OFFERING_1,
                PRODUCT_OFFERING_2,
                PRODUCT_OFFERING_3,
                PRODUCT_OFFERING_4
        };
    }

    public static String[] scorpioFullWithCategoryJsonArray() {
        return new String[]{
                PRODUCT_OFFERING_1,
                PRODUCT_OFFERING_2,
                PRODUCT_OFFERING_3,
                PRODUCT_OFFERING_4,
                CATEGORY
        };
    }
}
