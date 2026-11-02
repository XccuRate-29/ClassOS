FROM ubuntu:24.04 AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends g++ cmake ninja-build ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
 && cmake --build build \
 && ctest --test-dir build --output-on-failure

FROM ubuntu:24.04
COPY --from=build /src/build/classos_solver_server /usr/local/bin/classos_solver_server
ENV SOLVER_HOST=0.0.0.0 SOLVER_PORT=7070
EXPOSE 7070
USER nobody
CMD ["classos_solver_server"]
