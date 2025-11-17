# Contributing to Netty-SocketIO

Thank you for your interest in contributing to Netty-SocketIO! This document provides guidelines for contributors.

## Project Structure

The project uses a multi-module Maven structure:

- **netty-socketio-core**: Core implementation module
- **netty-socketio-spring**: Spring integration module
- **netty-socketio-spring-boot-starter**: Spring Boot Starter
- **netty-socketio-quarkus**: Quarkus integration module (includes deployment and runtime submodules)
- **netty-socketio-micronaut**: Micronaut integration module
- **netty-socketio-smoke-test**: Performance testing module
- **netty-socketio-examples**: Example projects

## Development Environment

### Prerequisites

- **Java 11+** (required for building module-info)
- **Java 8+** (minimum runtime requirement)
- **Maven 3.0.5+**
- **Git**

### Quick Start

```bash
# Fork and clone the repository
git clone https://github.com/YOUR_USERNAME/netty-socketio.git
cd netty-socketio
git remote add upstream https://github.com/socketio4j/netty-socketio.git

# Build the project
mvn clean compile

# Run tests
mvn test

# Full verification
mvn clean verify
```

## Coding Standards

### Code Style

The project uses Checkstyle for code quality checks. Configuration file is `checkstyle.xml`.

**Key guidelines:**
- Follow Java naming conventions
- Use 4 spaces for indentation (no tabs)
- Maximum method parameters: 10
- No trailing whitespace
- No unused imports
- Use meaningful variable and method names

### Code Quality Checks

```bash
mvn checkstyle:check
mvn pmd:check
mvn license:check
```

### License Headers

All source files must include the Apache 2.0 license header. Template is in `header.txt`.

## Testing

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=BasicConnectionTest

# Run integration tests
mvn test -Dtest=*IntegrationTest
```

### Writing Tests

- Use JUnit 5 for new tests. JUnit 4 is also supported for legacy tests (both are available via Maven dependencies).
- Use JMockit for mocking
- Follow AAA pattern (Arrange, Act, Assert)
- Include both positive and negative test cases
- Test edge cases and error conditions

## Submitting Pull Requests

### Before Submitting

1. Search for related issues or discussions
2. Create a feature branch (from `main` branch)
3. Ensure code passes all quality checks
4. Add tests for new functionality
5. Update relevant documentation


### Commit Messages

Follow conventional commit format:

```
type(scope): description
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Test changes
- `chore`: Build/tooling changes

**Examples:**
```
feat(transport): add WebSocket compression support
fix(parser): handle malformed JSON packets gracefully
docs(readme): update installation instructions
```

### PR Checklist

- [ ] Code follows project coding standards
- [ ] All tests pass locally
- [ ] New tests added for new functionality
- [ ] Documentation updated if needed
- [ ] Commit messages follow conventional format
- [ ] Branch is up to date with main
- [ ] No merge conflicts

## Reporting Issues

**Bug reports should include:**
- Clear description of the issue
- Steps to reproduce
- Expected vs actual behavior
- Environment details (Java version, OS, etc.)
- Minimal code example if applicable
- Logs or stack traces

**Feature requests should include:**
- Clear description of the feature
- Use case and motivation
- Proposed implementation approach (if any)
- Backward compatibility considerations

## Community Guidelines

- Respect all community members
- Provide constructive feedback
- Be patient (maintainers are volunteers)
- Provide complete information in issues and PRs
- Work together to improve the project

## Additional Resources

- [Socket.IO Protocol Documentation](https://socket.io/docs/v4/)
- [Netty Documentation](https://netty.io/wiki/)
- [Maven Guide](https://maven.apache.org/guides/getting-started/)

If you have questions, please check this document first, search existing issues, or create a new issue.

Thank you for contributing to Netty-SocketIO! 🚀
