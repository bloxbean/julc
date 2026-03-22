Always verify the implementation with the plan for correctness and completessness. Ensure all test cases are covered, including edge cases and error handling scenarios. Use both unit tests for individual components and integration tests for the overall system. Regularly review and update the test suite as the implementation evolves to maintain high code quality and reliability.

For integration test, use yaci devkit. Yaci Devkit needs to be started externally manually by user.

Yaci devkit can be controlled (reset, topup) using admin API endpoint running on port 10000. For example, to reset the devnet:

```bash
curl -X POST http://localhost:10000/local-cluster/api/admin/devnet/reset
```

Full openapi doc for Yaci Devkit API END points is available at http://localhost:10000/v3/api-docs

## Testing Best Practices
- Don't forget to test edge cases, such as invalid inputs, boundary conditions, and error scenarios. This will help ensure that the implementation is robust and can handle unexpected situations gracefully.
- Use a combination of unit tests for individual functions and integration tests that simulate real-world usage scenarios. This will help catch issues that may arise from the interaction between different components of the system.
- Regularly review and update the test cases as the implementation evolves to ensure that they remain relevant
- Reuse test data and setup code where possible to avoid duplication and make the tests easier to maintain. Consider using test fixtures or helper functions to set up common scenarios.

## Coding best practices
- Follow consistent coding conventions and style guidelines to improve readability and maintainability of the codebase.
- Use meaningful variable and function names that clearly convey their purpose and intent.
- Reuse code where possible to avoid duplication and improve maintainability. Consider using helper functions or classes to encapsulate common logic.
- Regularly review and refactor the code to improve its structure and readability. This will help ensure that the codebase remains clean and maintainable as it evolves over time.

## Paths

- `julc-examples` - Test projects with validator and off-chain code examples, plus unit tests. ../julc-examples
